/*
 * Copyright (c) 2009, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.xa;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TransactionState;
import org.postgresql.ds.PGPooledConnection;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.XAConnection;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

/**
 * The PostgreSQL implementation of {@link XAResource}.
 *
 * <p>This implementation doesn't support transaction interleaving (see JTA specification, section
 * 3.4.4) and suspend/resume.</p>
 *
 * <p>Two-phase commit requires PostgreSQL server version 8.1 or higher.</p>
 *
 * <p>Every statement this class issues goes through
 * {@link org.postgresql.core.BaseConnection#execSQLUpdate(String) execSQLUpdate} or
 * {@link org.postgresql.core.BaseConnection#execSQLQuery(String) execSQLQuery}, both of which set
 * {@code QueryExecutor.QUERY_SUPPRESS_BEGIN}. The driver therefore prepends no {@code BEGIN} of its
 * own, and the caller's JDBC {@code autoCommit} flag keeps its value across every
 * {@code XAResource} call.</p>
 *
 * <p>{@link #getConnection()} is the one exception. When {@code state == IDLE} it sets
 * {@code autoCommit=true} on the handle it returns, since that handle is outside the XA protocol
 * and {@code true} is the JDBC default.</p>
 *
 * @author Heikki Linnakangas (heikki.linnakangas@iki.fi)
 */
public class PGXAConnection extends PGPooledConnection implements XAConnection, XAResource {

  private static final Logger LOGGER = Logger.getLogger(PGXAConnection.class.getName());

  /**
   * Underlying physical database connection. It's used for issuing PREPARE TRANSACTION/ COMMIT
   * PREPARED/ROLLBACK PREPARED commands.
   */
  private final BaseConnection conn;

  private @Nullable Xid currentXid;

  private State state;
  private @Nullable Xid preparedXid;
  private boolean committedOrRolledBack;

  private void debug(String s) {
    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, "XAResource {0}: {1}", new Object[]{Integer.toHexString(this.hashCode()), s});
    }
  }

  public PGXAConnection(BaseConnection conn) throws SQLException {
    super(conn, true, true);
    this.conn = conn;
    this.state = State.IDLE;
  }

  /**
   * XAConnection interface.
   */
  @Override
  public Connection getConnection() throws SQLException {
    Connection conn = super.getConnection();

    // The handle starts at autoCommit=true only when no branch is open; inside a branch the
    // caller's own value stands.
    if (state == State.IDLE) {
      conn.setAutoCommit(true);
    }

    /*
     * Wrap the connection in a proxy to forbid application from fiddling with transaction state
     * directly during an XA transaction
     */
    ConnectionHandler handler = new ConnectionHandler(conn);
    return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class[]{Connection.class, PGConnection.class}, handler);
  }

  @Override
  public XAResource getXAResource() {
    return this;
  }

  /*
   * A java.sql.Connection proxy class to forbid calls to transaction control methods while the
   * connection is used for an XA transaction.
   */
  private class ConnectionHandler implements InvocationHandler {
    private final Connection con;

    ConnectionHandler(Connection con) {
      this.con = con;
    }

    @Override
    @SuppressWarnings("throwing.nullable")
    public @Nullable Object invoke(Object proxy, Method method, @Nullable Object[] args) throws Throwable {
      if (state != State.IDLE) {
        String methodName = method.getName();
        if ("commit".equals(methodName)
            || "rollback".equals(methodName)
            || "setSavepoint".equals(methodName)
            || "setAutoCommit".equals(methodName)) {
          throw new PSQLException(
              GT.tr(
                  "Transaction control methods setAutoCommit, commit, rollback and setSavepoint are not allowed while an XA transaction is active."),
              PSQLState.OBJECT_NOT_IN_STATE);
        }
      }
      try {
        /*
         * If the argument to equals-method is also a wrapper, present the original unwrapped
         * connection to the underlying equals method.
         */
        if ("equals".equals(method.getName()) && args.length == 1) {
          Object arg = args[0];
          if (arg != null && Proxy.isProxyClass(arg.getClass())) {
            InvocationHandler h = Proxy.getInvocationHandler(arg);
            if (h instanceof ConnectionHandler) {
              // unwrap argument
              args = new Object[]{((ConnectionHandler) h).con};
            }
          }
        }

        return method.invoke(con, args);
      } catch (InvocationTargetException ex) {
        throw ex.getTargetException();
      }
    }
  }

  /**
   * Preconditions:
   * <ol>
   *     <li>Flags must be one of TMNOFLAGS, TMRESUME or TMJOIN</li>
   *     <li>xid != null</li>
   *     <li>Connection must not be associated with a transaction</li>
   *     <li>The TM hasn't seen the xid before</li>
   * </ol>
   *
   * <p>Implementation deficiency preconditions:</p>
   * <ol>
   *     <li>TMRESUME not supported.</li>
   *     <li>If flags is TMJOIN, we must be in ended state, and xid must be the current transaction</li>
   *     <li>Unless flags is TMJOIN, previous transaction using the connection must be committed or prepared or rolled
   *     back</li>
   * </ol>
   *
   * <p>Postconditions:</p>
   * <ol>
   *     <li>Connection is associated with the transaction</li>
   * </ol>
   */
  @Override
  public void start(Xid xid, int flags) throws XAException {
    if (LOGGER.isLoggable(Level.FINEST)) {
      debug("starting transaction xid = " + xid);
    }

    // Check preconditions
    if (flags != XAResource.TMNOFLAGS && flags != XAResource.TMRESUME
        && flags != XAResource.TMJOIN) {
      throw new PGXAException(GT.tr("Invalid flags {0}", flags), XAException.XAER_INVAL);
    }

    if (xid == null) {
      throw new PGXAException(GT.tr("xid must not be null"), XAException.XAER_INVAL);
    }

    if (state == State.ACTIVE) {
      throw new PGXAException(
          GT.tr("Connection is already associated with an active XA branch. End the current branch before starting a new one. start xid={0}, currentXid={1}, state={2}, flags={3}",
              xid, currentXid, state, flags),
          XAException.XAER_PROTO);
    }

    // We can't check precondition 4 easily, so we don't. Duplicate xid will be caught in prepare
    // phase.

    // Check implementation deficiency preconditions
    if (flags == TMRESUME) {
      throw new PGXAException(GT.tr("Suspend/resume not implemented"), XAException.XAER_RMERR);
    }

    // It's ok to join an ended transaction. WebLogic does that.
    if (flags == TMJOIN) {
      if (state != State.ENDED) {
        throw new PGXAException(
            GT.tr(
                "Invalid protocol state requested. Attempted transaction interleaving is not supported. xid={0}, currentXid={1}, state={2}, flags={3}",
                xid, currentXid, state, flags), XAException.XAER_RMERR);
      }

      if (!xid.equals(currentXid)) {
        throw new PGXAException(
            GT.tr(
                "Invalid protocol state requested. Attempted transaction interleaving is not supported. xid={0}, currentXid={1}, state={2}, flags={3}",
                xid, currentXid, state, flags), XAException.XAER_RMERR);
      }
    } else if (state == State.ENDED) {
      throw new PGXAException(GT.tr("Invalid protocol state requested. Attempted transaction interleaving is not supported. xid={0}, currentXid={1}, state={2}, flags={3}", xid, currentXid, state, flags),
          XAException.XAER_RMERR);
    }

    // TMNOFLAGS opens a fresh server-side transaction with an explicit BEGIN. TMJOIN attaches to an
    // existing branch that end() left behind, where the prior start(TMNOFLAGS) already sent that
    // BEGIN, so this path issues no SQL.
    if (flags == TMNOFLAGS) {
      try {
        conn.execSQLUpdate("BEGIN");
      } catch (SQLException ex) {
        throw new PGXAException(GT.tr("Error opening transaction. start xid={0}", xid), ex,
            XAException.XAER_RMERR);
      }
    }

    // Preconditions are met, Associate connection with the transaction
    state = State.ACTIVE;
    currentXid = xid;
    preparedXid = null;
    committedOrRolledBack = false;
  }

  /**
   * Preconditions:
   * <ol>
   *     <li>Flags is one of TMSUCCESS, TMFAIL, TMSUSPEND</li>
   *     <li>xid != null</li>
   *     <li>Connection is associated with transaction xid</li>
   * </ol>
   *
   * <p>Implementation deficiency preconditions:</p>
   * <ol>
   *     <li>Flags is not TMSUSPEND</li>
   * </ol>
   *
   * <p>Postconditions:</p>
   * <ol>
   *     <li>Connection is disassociated from the transaction.</li>
   * </ol>
   */
  @Override
  public void end(Xid xid, int flags) throws XAException {
    if (LOGGER.isLoggable(Level.FINEST)) {
      debug("ending transaction xid = " + xid);
    }

    // Check preconditions

    if (flags != XAResource.TMSUSPEND && flags != XAResource.TMFAIL
        && flags != XAResource.TMSUCCESS) {
      throw new PGXAException(GT.tr("Invalid flags {0}", flags), XAException.XAER_INVAL);
    }

    if (xid == null) {
      throw new PGXAException(GT.tr("xid must not be null"), XAException.XAER_INVAL);
    }

    if (state != State.ACTIVE || !xid.equals(currentXid)) {
      throw new PGXAException(GT.tr("end() called without a matching start(). end xid={0}, currentXid={1}, state={2}, preparedXid={3}",
              xid, currentXid, state, preparedXid),
          XAException.XAER_PROTO);
    }

    // Check implementation deficiency preconditions
    if (flags == XAResource.TMSUSPEND) {
      throw new PGXAException(GT.tr("Suspend/resume not implemented"), XAException.XAER_RMERR);
    }

    // We ignore TMFAIL. It's just a hint to the RM. We could roll back immediately
    // if TMFAIL was given.

    // All clear. We don't have any real work to do.
    state = State.ENDED;
  }

  /**
   * Prepares transaction. Preconditions:
   * <ol>
   *     <li>xid != null</li>
   *     <li>xid is in ended state</li>
   * </ol>
   *
   * <p>Implementation deficiency preconditions:</p>
   * <ol>
   *     <li>xid was associated with this connection</li>
   * </ol>
   *
   * <p>Postconditions:</p>
   * <ol>
   *     <li>Transaction is prepared</li>
   * </ol>
   */
  @Override
  public int prepare(Xid xid) throws XAException {
    if (LOGGER.isLoggable(Level.FINEST)) {
      debug("preparing transaction xid = " + xid);
    }

    // Check preconditions
    if (currentXid == null && preparedXid != null) {
      if (LOGGER.isLoggable(Level.FINEST)) {
        debug("Prepare xid " + xid + " but current connection is not attached to a transaction"
            + " while it was prepared in past with prepared xid " + preparedXid);
      }
      throw new PGXAException(GT.tr(
          "Transaction was already prepared on this connection. prepare xid={0}, preparedXid={1}", xid, preparedXid),
          XAException.XAER_PROTO);
    } else if (currentXid == null) {
      throw new PGXAException(GT.tr(
          "Current connection does not have an associated xid. prepare xid={0}", xid), XAException.XAER_NOTA);
    }
    if (!currentXid.equals(xid)) {
      if (LOGGER.isLoggable(Level.FINEST)) {
        debug("Error to prepare xid " + xid + ", the current connection already bound with xid " + currentXid);
      }
      throw new PGXAException(GT.tr(
          "Prepare must be issued on the connection that started the branch. Transaction interleaving is not supported. prepare xid={0}, currentXid={1}", xid, currentXid),
          XAException.XAER_RMERR);
    }
    if (state != State.ENDED) {
      throw new PGXAException(GT.tr("Prepare called before end(). prepare xid={0}, state={1}", xid, state),
          XAException.XAER_INVAL);
    }

    try {
      String s = RecoveredXid.xidToString(xid);
      conn.execSQLUpdate("PREPARE TRANSACTION '" + s + "'");
    } catch (SQLException ex) {
      // The XA state changes only after PREPARE TRANSACTION succeeds, so a failure leaves state at
      // ENDED with currentXid set. The transaction manager can then recover by calling
      // rollback(xid), which takes the active-branch path and issues a plain ROLLBACK against the
      // still-open server transaction.
      throw new PGXAException(GT.tr("Error preparing transaction. prepare xid={0}", xid), ex, mapSQLStateToXAErrorCode(ex));
    }

    state = State.IDLE;
    preparedXid = currentXid;
    currentXid = null;

    try {
      if (conn.isReadOnly()) {
        return XA_RDONLY;
      } else {
        return XA_OK;
      }
    } catch (SQLException ex) {
      throw new PGXAException(GT.tr("Error preparing transaction. prepare xid={0}", xid), ex, mapSQLStateToXAErrorCode(ex));
    }
  }

  /**
   * Recovers transaction. Preconditions:
   * <ol>
   *     <li>flag must be one of TMSTARTRSCAN, TMENDRSCAN, TMNOFLAGS or TMSTARTTRSCAN | TMENDRSCAN</li>
   *     <li>If flag isn't TMSTARTRSCAN or TMSTARTRSCAN | TMENDRSCAN, a recovery scan must be in progress</li>
   * </ol>
   *
   * <p>Postconditions:</p>
   * <ol>
   *     <li>list of prepared xids is returned</li>
   * </ol>
   */
  @Override
  public Xid[] recover(int flag) throws XAException {
    if (LOGGER.isLoggable(Level.FINEST)) {
      debug("recover called with flag=" + flag);
    }

    // Check preconditions
    if (flag != TMSTARTRSCAN && flag != TMENDRSCAN && flag != TMNOFLAGS
        && flag != (TMSTARTRSCAN | TMENDRSCAN)) {
      throw new PGXAException(GT.tr("Invalid flags {0}", flag), XAException.XAER_INVAL);
    }

    // We don't check for precondition 2, because we would have to add some additional state in
    // this object to keep track of recovery scans.

    // All clear. We return all the xids in the first TMSTARTRSCAN call, and always return
    // an empty array otherwise.
    if ((flag & TMSTARTRSCAN) == 0) {
      return new Xid[0];
    }

    // A local transaction that the caller already opened on this connection stays open across this
    // SELECT, which runs inside it as a metadata read.
    //
    // PostgreSQL restricts COMMIT PREPARED and ROLLBACK PREPARED to the transaction's owner, so
    // the WHERE clause keeps only the rows for which pg_has_role(current_user, owner, 'member').
    // See https://github.com/postgres/postgres/blob/15afb7d61c142a9254a6612c6774aff4f358fb69/src/backend/access/transam/twophase.c#L583C32-L599
    try (ResultSet rs = conn.execSQLQuery(
        "SELECT gid FROM pg_prepared_xacts where database = current_database() and pg_has_role(current_user, owner, 'member')")) {
      List<Xid> l = new ArrayList<>();
      while (rs.next()) {
        Xid recoveredXid = RecoveredXid.stringToXid(castNonNull(rs.getString(1)));
        if (recoveredXid != null) {
          l.add(recoveredXid);
        }
      }
      if (LOGGER.isLoggable(Level.FINEST)) {
        debug("recover returning " + l.size() + " prepared xid(s)");
      }
      return l.toArray(new Xid[0]);
    } catch (SQLException ex) {
      throw new PGXAException(GT.tr("Error during recover. flag={0}", flag), ex, XAException.XAER_RMERR);
    }
  }

  /**
   * Preconditions:
   * <ol>
   *     <li>xid is known to the RM or it's in prepared state</li>
   * </ol>
   *
   * <p>Implementation deficiency preconditions:</p>
   * <ol>
   *     <li>xid must be associated with this connection if it's not in prepared state.</li>
   * </ol>
   *
   * <p>Postconditions:</p>
   * <ol>
   *     <li>Transaction is rolled back and disassociated from connection</li>
   * </ol>
   */
  @Override
  public void rollback(Xid xid) throws XAException {
    if (LOGGER.isLoggable(Level.FINEST)) {
      debug("rolling back xid = " + xid);
    }

    // We don't explicitly check precondition 1.

    try {
      if (currentXid != null && currentXid.equals(xid)) {
        // The branch is still active, so a plain ROLLBACK closes the server transaction that
        // start() opened. The server accepts ROLLBACK inside an aborted transaction block, so this
        // path works when the driver's state is TransactionState.FAILED too.
        conn.execSQLUpdate("ROLLBACK");
        state = State.IDLE;
        currentXid = null;
      } else {
        // The branch was prepared, and PostgreSQL rejects ROLLBACK PREPARED inside a transaction
        // block. Refusing the call keeps the caller's local transaction intact, and XAER_RMFAIL
        // lets the transaction manager retry on a fresh XAResource.
        if (conn.getTransactionState() != TransactionState.IDLE) {
          if (LOGGER.isLoggable(Level.FINEST)) {
            debug("rollback prepared rejected: local transaction in progress. rollback xid=" + xid
                + ", transactionState=" + conn.getTransactionState());
          }
          throw new PGXAException(
              GT.tr("Cannot rollback prepared transaction while a local transaction is in progress on this connection. "
                  + "Commit or rollback the local transaction first. rollback xid={0}, transactionState={1}",
                  xid, conn.getTransactionState()),
              XAException.XAER_RMFAIL);
        }
        String s = RecoveredXid.xidToString(xid);
        conn.execSQLUpdate("ROLLBACK PREPARED '" + s + "'");
      }
      committedOrRolledBack = true;
    } catch (SQLException ex) {
      int errorCode = XAException.XAER_RMERR;
      if (PSQLState.UNDEFINED_OBJECT.getState().equals(ex.getSQLState())) {
        if (committedOrRolledBack || !xid.equals(preparedXid)) {
          if (LOGGER.isLoggable(Level.FINEST)) {
            debug("rolling back xid " + xid + " while the connection prepared xid is " + preparedXid
                + (committedOrRolledBack ? ", but the connection was already committed/rolled-back" : ""));
          }
          errorCode = XAException.XAER_NOTA;
        }
      }
      if (PSQLState.isConnectionError(ex.getSQLState())) {
        if (LOGGER.isLoggable(Level.FINEST)) {
          debug("rollback connection failure (sql error code " + ex.getSQLState() + "), reconnection could be expected");
        }
        errorCode = XAException.XAER_RMFAIL;
      }
      throw new PGXAException(GT.tr("Error rolling back transaction. rollback xid={0}, preparedXid={1}, currentXid={2}", xid, preparedXid, currentXid), ex, errorCode);
    }
  }

  @Override
  public void commit(Xid xid, boolean onePhase) throws XAException {
    if (LOGGER.isLoggable(Level.FINEST)) {
      debug("committing xid = " + xid + (onePhase ? " (one phase) " : " (two phase)"));
    }

    if (xid == null) {
      throw new PGXAException(GT.tr("xid must not be null"), XAException.XAER_INVAL);
    }

    if (onePhase) {
      commitOnePhase(xid);
    } else {
      commitPrepared(xid);
    }
  }

  /**
   * Preconditions:
   * <ol>
   *     <li>xid must in ended state.</li>
   * </ol>
   *
   * <p>Implementation deficiency preconditions:</p>
   * <ol>
   *     <li>this connection must have been used to run the transaction</li>
   * </ol>
   *
   * <p>Postconditions:</p>
   * <ol>
   *     <li>Transaction is committed</li>
   * </ol>
   */
  private void commitOnePhase(Xid xid) throws XAException {
    if (xid.equals(preparedXid)) { // TODO: check if the condition should be negated
      throw new PGXAException(GT.tr("One-phase commit called for xid {0} but connection was prepared with xid {1}",
          xid, preparedXid), XAException.XAER_PROTO);
    }
    if (currentXid == null && !committedOrRolledBack) {
      // Either the xid is unknown to this resource manager, or the caller routed the commit to the
      // wrong connection. The error reports the routing mistake, which is the common
      // application-server bug.
      throw new PGXAException(GT.tr(
          "One-phase commit must be issued on the connection that started the branch. commit xid={0}", xid),
          XAException.XAER_RMERR);
    }
    if (!xid.equals(currentXid) || committedOrRolledBack) {
      throw new PGXAException(GT.tr("One-phase commit with unknown xid. commit xid={0}, currentXid={1}",
          xid, currentXid), XAException.XAER_NOTA);
    }
    if (state != State.ENDED) {
      throw new PGXAException(GT.tr("commit() called before end(). commit xid={0}, state={1}", xid, state),
          XAException.XAER_PROTO);
    }

    // conn.commit() is unusable here: PgConnection.commit() throws when autoCommit is true, and
    // autoCommit keeps whatever value the caller set.
    try {
      conn.execSQLUpdate("COMMIT");
    } catch (SQLException ex) {
      // The XA state changes only after COMMIT succeeds, so a failure leaves state at ENDED with
      // currentXid set and the transaction manager can recover by calling rollback(xid).
      throw new PGXAException(GT.tr("Error during one-phase commit. commit xid={0}", xid), ex, mapSQLStateToXAErrorCode(ex));
    }

    state = State.IDLE;
    currentXid = null;
    committedOrRolledBack = true;
  }

  /**
   * Commits prepared transaction. Preconditions:
   * <ol>
   *     <li>xid must be in prepared state in the server</li>
   * </ol>
   *
   * <p>Implementation deficiency preconditions:</p>
   * <ol>
   *     <li>Connection must be in idle state</li>
   * </ol>
   *
   * <p>Postconditions:</p>
   * <ol>
   *     <li>Transaction is committed</li>
   * </ol>
   */
  private void commitPrepared(Xid xid) throws XAException {
    // The XA state of this XAResource must be IDLE. A prepared transaction cannot be committed
    // while a different XA branch is still active on this connection.
    if (state != State.IDLE) {
      if (LOGGER.isLoggable(Level.FINEST)) {
        debug("2-phase commit rejected: XA branch active. commit xid=" + xid
            + ", currentXid=" + currentXid + ", state=" + state);
      }
      throw new PGXAException(
          GT.tr("2nd phase commit cannot be issued while an XA branch is active on this connection. "
              + "commit xid={0}, currentXid={1}, state={2}", xid, currentXid, state),
          XAException.XAER_PROTO);
    }
    // The underlying connection must also be outside any local transaction: PostgreSQL rejects
    // COMMIT PREPARED inside a transaction block, and refusing the call keeps the caller's local
    // work intact. XAER_RMFAIL lets the transaction manager retry on a fresh XAResource.
    if (conn.getTransactionState() != TransactionState.IDLE) {
      if (LOGGER.isLoggable(Level.FINEST)) {
        debug("2-phase commit rejected: local transaction in progress. commit xid=" + xid
            + ", transactionState=" + conn.getTransactionState());
      }
      throw new PGXAException(
          GT.tr("Cannot 2nd phase commit prepared transaction while a local transaction is in progress on this connection. "
              + "Commit or rollback the local transaction first. commit xid={0}, transactionState={1}",
              xid, conn.getTransactionState()),
          XAException.XAER_RMFAIL);
    }

    try {
      String s = RecoveredXid.xidToString(xid);
      conn.execSQLUpdate("COMMIT PREPARED '" + s + "'");
      committedOrRolledBack = true;
    } catch (SQLException ex) {
      int errorCode = XAException.XAER_RMERR;
      if (PSQLState.UNDEFINED_OBJECT.getState().equals(ex.getSQLState())) {
        if (committedOrRolledBack || !xid.equals(preparedXid)) {
          if (LOGGER.isLoggable(Level.FINEST)) {
            debug("committing xid " + xid + " while the connection prepared xid is " + preparedXid
                + (committedOrRolledBack ? ", but the connection was already committed/rolled-back" : ""));
          }
          errorCode = XAException.XAER_NOTA;
        }
      }
      if (PSQLState.isConnectionError(ex.getSQLState())) {
        if (LOGGER.isLoggable(Level.FINEST)) {
          debug("commit connection failure (sql error code " + ex.getSQLState() + "), reconnection could be expected");
        }
        errorCode = XAException.XAER_RMFAIL;
      }
      throw new PGXAException(GT.tr("Error committing prepared transaction. commit xid={0}, preparedXid={1}, currentXid={2}", xid, preparedXid, currentXid), ex, errorCode);
    }
  }

  @Override
  public boolean isSameRM(XAResource xares) throws XAException {
    // This trivial implementation makes sure that the
    // application server doesn't try to use another connection
    // for prepare, commit and rollback commands.
    return xares == this;
  }

  /**
   * Does nothing, since we don't do heuristics.
   */
  @Override
  public void forget(Xid xid) throws XAException {
    throw new PGXAException(GT.tr("Heuristic commit/rollback not supported. forget xid={0}", xid),
        XAException.XAER_NOTA);
  }

  /**
   * We don't do transaction timeouts. Just returns 0.
   */
  @Override
  public int getTransactionTimeout() {
    return 0;
  }

  /**
   * We don't do transaction timeouts. Returns false.
   */
  @Override
  public boolean setTransactionTimeout(int seconds) {
    return false;
  }

  private static int mapSQLStateToXAErrorCode(SQLException sqlException) {
    if (isPostgreSQLIntegrityConstraintViolation(sqlException)) {
      return XAException.XA_RBINTEGRITY;
    }

    return XAException.XAER_RMFAIL;
  }

  private static boolean isPostgreSQLIntegrityConstraintViolation(SQLException sqlException) {
    if (!(sqlException instanceof PSQLException)) {
      return false;
    }
    String sqlState = sqlException.getSQLState();
    return sqlState != null
        && sqlState.length() == 5
        && sqlState.startsWith("23"); // Class 23 - Integrity Constraint Violation
  }

  private enum State {
    /**
     * {@code PGXAConnection} not associated with a XA-transaction. You can still call {@link #getConnection()} and
     * use the connection outside XA. {@code currentXid} is {@code null}. autoCommit is {@code true} on a connection
     * by getConnection, per normal JDBC rules, though the caller can change it to {@code false} and manage
     * transactions itself using Connection.commit and rollback.
     */
    IDLE,
    /**
     * {@link #start(Xid, int)} has been called, and we're associated with an XA transaction. {@code currentXid}
     * is valid. {@link #getConnection()} leaves autoCommit at the value the caller set, and the handle it
     * returns rejects {@code setAutoCommit} until the branch ends.
     */
    ACTIVE,
    /**
     * {@link #end(Xid, int)} has been called, but the transaction has not yet been prepared. {@code currentXid}
     * is still valid. You shouldn't use the connection for anything else than issuing a {@link XAResource#commit(Xid, boolean)} or
     * rollback.
     */
    ENDED
  }

}
