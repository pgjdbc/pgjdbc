/*
 * Copyright (c) 2009, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.xa;

import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TransactionState;
import org.postgresql.ds.PGPooledConnection;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedList;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.XAConnection;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

/**
 * <p>The PostgreSQL implementation of {@link XAResource}.</p>
 *
 * <p>This implementation doesn't support transaction interleaving (see JTA specification, section
 * 3.4.4) and suspend/resume.</p>
 *
 * <p>Two-phase commit requires PostgreSQL server version 8.1 or higher.</p>
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

  private Xid currentXid;

  private State state;
  private Xid preparedXid;
  private boolean committedOrRolledBack;

  /*
   * When an XA transaction is started, we put the underlying connection into non-autocommit mode.
   * The old setting is saved in localAutoCommitMode, so that we can restore it when the XA
   * transaction ends and the connection returns into local transaction mode.
   */
  private boolean localAutoCommitMode = true;

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

    // When we're outside an XA transaction, autocommit
    // is supposed to be true, per usual JDBC convention.
    // When an XA transaction is in progress, it should be
    // false.
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
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (state != State.IDLE) {
        String methodName = method.getName();
        if (methodName.equals("commit")
            || methodName.equals("rollback")
            || methodName.equals("setSavePoint")
            || (methodName.equals("setAutoCommit") && (Boolean) args[0])) {
          throw new PSQLException(
              GT.tr(
                  "Transaction control methods setAutoCommit(true), commit, rollback and setSavePoint not allowed while an XA transaction is active."),
              PSQLState.OBJECT_NOT_IN_STATE);
        }
      }
      try {
        /*
         * If the argument to equals-method is also a wrapper, present the original unwrapped
         * connection to the underlying equals method.
         */
        if (method.getName().equals("equals")) {
          Object arg = args[0];
          if (Proxy.isProxyClass(arg.getClass())) {
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
   * <p>Preconditions:</p>
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
      throw new PGXAException(GT.tr("Connection is busy with another transaction"),
          XAException.XAER_PROTO);
    }

    // We can't check precondition 4 easily, so we don't. Duplicate xid will be catched in prepare
    // phase.

    // Check implementation deficiency preconditions
    if (flags == TMRESUME) {
      throw new PGXAException(GT.tr("suspend/resume not implemented"), XAException.XAER_RMERR);
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

    // Only need save localAutoCommitMode for NOFLAGS, TMRESUME and TMJOIN already saved old
    // localAutoCommitMode.
    if (flags == TMNOFLAGS) {
      try {
        localAutoCommitMode = conn.getAutoCommit();
        conn.setAutoCommit(false);
      } catch (SQLException ex) {
        throw new PGXAException(GT.tr("Error disabling autocommit"), ex, XAException.XAER_RMERR);
      }
    }

    // Preconditions are met, Associate connection with the transaction
    state = State.ACTIVE;
    currentXid = xid;
    preparedXid = null;
    committedOrRolledBack = false;
  }

  /**
   * <p>Preconditions:</p>
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

    if (state != State.ACTIVE || !currentXid.equals(xid)) {
      throw new PGXAException(GT.tr("tried to call end without corresponding start call. state={0}, start xid={1}, currentXid={2}, preparedXid={3}", state, xid, currentXid, preparedXid),
          XAException.XAER_PROTO);
    }

    // Check implementation deficiency preconditions
    if (flags == XAResource.TMSUSPEND) {
      throw new PGXAException(GT.tr("suspend/resume not implemented"), XAException.XAER_RMERR);
    }

    // We ignore TMFAIL. It's just a hint to the RM. We could roll back immediately
    // if TMFAIL was given.

    // All clear. We don't have any real work to do.
    state = State.ENDED;
  }

  /**
   * <p>Prepares transaction. Preconditions:</p>
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
          "Preparing already prepared transaction, the prepared xid {0}, prepare xid={1}", preparedXid, xid), XAException.XAER_PROTO);
    } else if (currentXid == null) {
      throw new PGXAException(GT.tr(
          "Current connection does not have an associated xid. prepare xid={0}", xid), XAException.XAER_NOTA);
    }
    if (!currentXid.equals(xid)) {
      if (LOGGER.isLoggable(Level.FINEST)) {
        debug("Error to prepare xid " + xid + ", the current connection already bound with xid " + currentXid);
      }
      throw new PGXAException(GT.tr(
          "Not implemented: Prepare must be issued using the same connection that started the transaction. currentXid={0}, prepare xid={1}", currentXid, xid),
          XAException.XAER_RMERR);
    }
    if (state != State.ENDED) {
      throw new PGXAException(GT.tr("Prepare called before end. prepare xid={0}, state={1}", xid), XAException.XAER_INVAL);
    }

    state = State.IDLE;
    preparedXid = currentXid;
    currentXid = null;

    try {
      String s = RecoveredXid.xidToString(xid);

      Statement stmt = conn.createStatement();
      try {
        stmt.executeUpdate("PREPARE TRANSACTION '" + s + "'");
      } finally {
        stmt.close();
      }
      conn.setAutoCommit(localAutoCommitMode);

      return XA_OK;
    } catch (SQLException ex) {
      throw new PGXAException(GT.tr("Error preparing transaction. prepare xid={0}", xid), ex, mapSQLStateToXAErrorCode(ex));
    }
  }

  /**
   * <p>Recovers transaction. Preconditions:</p>
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
    } else {
      try {
        Statement stmt = conn.createStatement();
        try {
          // If this connection is simultaneously used for a transaction,
          // this query gets executed inside that transaction. It's OK,
          // except if the transaction is in abort-only state and the
          // backed refuses to process new queries. Hopefully not a problem
          // in practise.
          ResultSet rs = stmt.executeQuery(
              "SELECT gid FROM pg_prepared_xacts where database = current_database()");
          LinkedList<Xid> l = new LinkedList<Xid>();
          while (rs.next()) {
            Xid recoveredXid = RecoveredXid.stringToXid(rs.getString(1));
            if (recoveredXid != null) {
              l.add(recoveredXid);
            }
          }
          rs.close();

          return l.toArray(new Xid[l.size()]);
        } finally {
          stmt.close();
        }
      } catch (SQLException ex) {
        throw new PGXAException(GT.tr("Error during recover"), ex, XAException.XAER_RMERR);
      }
    }
  }

  /**
   * <p>Preconditions:</p>
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
        state = State.IDLE;
        currentXid = null;
        conn.rollback();
        conn.setAutoCommit(localAutoCommitMode);
      } else {
        String s = RecoveredXid.xidToString(xid);

        conn.setAutoCommit(true);
        Statement stmt = conn.createStatement();
        try {
          stmt.executeUpdate("ROLLBACK PREPARED '" + s + "'");
        } finally {
          stmt.close();
        }
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
      throw new PGXAException(GT.tr("Error rolling back prepared transaction. rollback xid={0}, preparedXid={1}, currentXid={2}", xid, preparedXid), ex, errorCode);
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
   * <p>Preconditions:</p>
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
    try {
      // Check preconditions
      if (xid.equals(preparedXid)) { // TODO: check if the condition should be negated
        throw new PGXAException(GT.tr("One-phase commit called for xid {0} but connection was prepared with xid {1}",
            xid, preparedXid), XAException.XAER_PROTO);
      }
      if (currentXid == null && !committedOrRolledBack) {
        // In fact, we don't know if xid is bogus, or if it just wasn't associated with this connection.
        // Assume it's our fault.
        // TODO: pick proper error message. Current one does not clarify what went wrong
        throw new PGXAException(GT.tr(
            "Not implemented: one-phase commit must be issued using the same connection that was used to start it", xid),
            XAException.XAER_RMERR);
      }
      if (!xid.equals(currentXid) || committedOrRolledBack) {
        throw new PGXAException(GT.tr("One-phase commit with unknown xid. commit xid={0}, currentXid={1}",
            xid, currentXid), XAException.XAER_NOTA);
      }
      if (state != State.ENDED) {
        throw new PGXAException(GT.tr("commit called before end. commit xid={0}, state={1}", xid, state), XAException.XAER_PROTO);
      }

      // Preconditions are met. Commit
      state = State.IDLE;
      currentXid = null;
      committedOrRolledBack = true;

      conn.commit();
      conn.setAutoCommit(localAutoCommitMode);
    } catch (SQLException ex) {
      throw new PGXAException(GT.tr("Error during one-phase commit. commit xid={0}", xid), ex, mapSQLStateToXAErrorCode(ex));
    }
  }

  /**
   * <p>Commits prepared transaction. Preconditions:</p>
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
    try {
      // Check preconditions. The connection mustn't be used for another
      // other XA or local transaction, or the COMMIT PREPARED command
      // would mess it up.
      if (state != State.IDLE
          || conn.getTransactionState() != TransactionState.IDLE) {
        throw new PGXAException(
            GT.tr("Not implemented: 2nd phase commit must be issued using an idle connection. commit xid={0}, currentXid={1}, state={2], transactionState={3}", xid, currentXid, state, conn.getTransactionState()),
            XAException.XAER_RMERR);
      }

      String s = RecoveredXid.xidToString(xid);

      localAutoCommitMode = conn.getAutoCommit();
      conn.setAutoCommit(true);
      Statement stmt = conn.createStatement();
      try {
        stmt.executeUpdate("COMMIT PREPARED '" + s + "'");
      } finally {
        stmt.close();
        conn.setAutoCommit(localAutoCommitMode);
      }
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

  private int mapSQLStateToXAErrorCode(SQLException sqlException) {
    if (isPostgreSQLIntegrityConstraintViolation(sqlException)) {
      return XAException.XA_RBINTEGRITY;
    }

    return XAException.XAER_RMFAIL;
  }

  private boolean isPostgreSQLIntegrityConstraintViolation(SQLException sqlException) {
    return sqlException instanceof PSQLException
        && sqlException.getSQLState().length() == 5
        && sqlException.getSQLState().startsWith("23"); // Class 23 - Integrity Constraint Violation
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
     * is valid. autoCommit is false on a connection returned by getConnection, and should not be messed with by
     * the caller or the XA transaction will be broken.
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
