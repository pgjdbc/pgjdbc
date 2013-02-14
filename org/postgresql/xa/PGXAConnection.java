/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2011, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package org.postgresql.xa;

import org.postgresql.ds.PGPooledConnection;

import javax.sql.*;
import java.sql.*;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import org.postgresql.util.GT;
import org.postgresql.xa.jdbc3.AbstractJdbc3XADataSource;

/**
 * The PostgreSQL implementation of {@link XAResource}.
 * 
 * Two-phase commit requires PostgreSQL server version 8.1
 * or higher.
 * 
 * This implementation constitutes a 'Transactional Resource' in the JTA 1.0.1
 * specification. As such, it's an amalgamation of an XAConnection, which uses
 * connection pool semantics atop a 'logical' (or virtual) Connection to the 
 * database, which is backed by a real, 'physical' Connection maintained within
 * the XADataSource implementation. The implementation of XAResource provided 
 * by this implementation is thread-safe, shareable (amongst equivalent Resoures),
 * and implements the required functionality for transaction interleaving.
 * 
 * @author Heikki Linnakangas (heikki.linnakangas@iki.fi)
 * @author Bryan Varner (bvarner@polarislabs.com)
 */
public class PGXAConnection extends PGPooledConnection implements XAConnection, XAResource {
    
    private long logicalConnectionId;
    private String user;
    private AbstractJdbc3XADataSource dataSource;
    private boolean localAutoCommitMode;

    
    public PGXAConnection(final long logicalConnectionId, final String user, final Connection logicalConnection, AbstractJdbc3XADataSource dataSource) {
        super(logicalConnection, true, true);
        this.logicalConnectionId = logicalConnectionId;
        this.user = user;
        this.dataSource = dataSource;
        this.localAutoCommitMode = true;
    }
    
    /**
     * Returns a handle to the logical connection (we are pooled, remember)
     * 
     * @return
     * @throws SQLException 
     */
    @Override
    public Connection getConnection() throws SQLException {
        // When we're outside an XA transaction, autocommit
        // is supposed to be true, per usual JDBC convention.
        // When an XA transaction is in progress, it should be
        // false.
        Connection logicalConn = super.getConnection();
        logicalConn.setAutoCommit(true);
        
        return logicalConn;
    }

    @Override
    public XAResource getXAResource() {
        return this;
    }

    @Override
    public void close() throws SQLException {
        try {
            super.close();
        } finally {
            dataSource = null;
        }
    }

    /**** XAResource interface ****/
    /**
     * 
     * @param xid
     * @param flags
     * @throws XAException 
     */
    public void start(Xid xid, int flags) throws XAException {
        if (flags != XAResource.TMNOFLAGS && flags != XAResource.TMRESUME && flags != XAResource.TMJOIN) {
            throw new PGXAException(GT.tr("Invalid flags"), XAException.XAER_INVAL);
        }

        if (xid == null) {
            throw new PGXAException(GT.tr("xid must not be null"), XAException.XAER_INVAL);
        }


        // TODO: Associate the logicalConnectionId with a physical connection pegged to this xid in the dataSource.
        
        try {
            localAutoCommitMode = getBackingConnection().getAutoCommit();
            getBackingConnection().setAutoCommit(false);
        } catch (SQLException ex) {
            throw new PGXAException(GT.tr("Error disabling autocommit"), ex, XAException.XAER_RMERR);
        }
    }
    
    /**
     * 
     * @param xid
     * @param flags
     * @throws XAException 
     */
    public void end(Xid xid, int flags) throws XAException {
        if (flags != XAResource.TMSUSPEND && flags != XAResource.TMFAIL && flags != XAResource.TMSUCCESS) {
            throw new PGXAException(GT.tr("Invalid flags"), XAException.XAER_INVAL);
        }

        if (xid == null) {
            throw new PGXAException(GT.tr("xid must not be null"), XAException.XAER_INVAL);
        }

        // TMSUSPEND, TMSUCCESS, TMFAIL are more or less hints to this resource manager. We don't have to do anything special to handle them.
        
        // TODO: Disassociate the logicalConnectionId with the physical connection pegged to this xid in the dataSource.
        
    }

    /**
     * While the PG server doesn't really do heuristics, it's possible for a
     * sysadmin to do a manual rollback / commit of a prepared transaction, 
     * effectively performing a heuristic decision, and leaving a backend 
     * physical connection in the dataSource pegged to the now-defuct Xid.
     * 
     * For that reason, we're implementing this method.
     * 
     * @param xid
     * @throws XAException 
     */
    public void forget(Xid xid) throws XAException {
        // TODO: Find a physical connection pegged to the given xid, if there is
        // one, close it (really, kill the physical connection) and open a new 
        // unassociated physical connection to take it's place.
    }

    /**
     * 
     * @param xid
     * @param onePhase
     * @throws XAException 
     */
    public void commit(Xid xid, boolean onePhase) throws XAException {
        if (xid == null) {
            throw new PGXAException(GT.tr("xid must not be null"), XAException.XAER_INVAL);
        }

        if (onePhase) {
            commitOnePhase(xid);
        } else {
            commitPrepared(xid);
        }
    }

    private void commitOnePhase(Xid xid) throws XAException {
//        try {
            // Locate the physical connection servicing the given xid, 
            // TODO: physConn.commit();
            
            // TODO: Save the localAutoCommitMode in the PhysicalXAConnection.
            // physConn.setAutoCommit(localAutoCommitMode);
//        } catch (SQLException ex) {
//            throw new PGXAException(GT.tr("Error during one-phase commit"), ex, XAException.XAER_RMERR);
//        }
    }

    private void commitPrepared(Xid xid) throws XAException {
//        try {
//            // TODO: Find either a physical connection which serviced the xid, 
//            // or any idle (available) physical connection.
//            
//            String s = RecoveredXid.xidToString(xid);
//
//            // TODO: Again, do this right.
//            localAutoCommitMode = physConn.getAutoCommit();
//            physConn.setAutoCommit(true);
//            Statement stmt = physConn.createStatement();
//            try {
//                stmt.executeUpdate("COMMIT PREPARED '" + s + "'");
//            } finally {
//                stmt.close();
//                physConn.setAutoCommit(localAutoCommitMode);
//            }
//        } catch (SQLException ex) {
//            throw new PGXAException(GT.tr("Error committing prepared transaction"), ex, XAException.XAER_RMERR);
//        }
    }
    
    /**
     * 
     * @param xares
     * @return
     * @throws XAException 
     */
    public boolean isSameRM(XAResource xares) throws XAException {
        if (xares == this) {
            return true;
        }
        
        // This is going to tell the TM that we can handle interleaving.
        if (xares instanceof PGXAConnection) {
            // we need to make sure that the dataSource is to the same server/port/username as the XAConnection we were created alongside.
            PGXAConnection other = (PGXAConnection)xares;
            if (other.dataSource.getServerName().equals(dataSource.getServerName()) &&
                other.dataSource.getPortNumber() == dataSource.getPortNumber() &&
                other.dataSource.getDatabaseName().equals(dataSource.getDatabaseName()) &&
                other.user.equals(user))
            {
                return true; // We're the same name, port, db, and user.
            }
        }
        
        return false;
    }

    /**
     * 
     * @param xid
     * @return
     * @throws XAException 
     */
    public int prepare(Xid xid) throws XAException {
        // TODO: Find the physical connection servicing the xid, then invoke the
        // these things on the physical connection.
        
//        
//        if (!physConn.haveMinimumServerVersion("8.1")) {
//            throw new PGXAException(GT.tr("Server versions prior to 8.1 do not support two-phase commit."), XAException.XAER_RMERR);
//        }
//
//        try {
//            String s = RecoveredXid.xidToString(xid);
//
//            Statement stmt = physConn.createStatement();
//            try {
//                stmt.executeUpdate("PREPARE TRANSACTION '" + s + "'");
//            } finally {
//                stmt.close();
//            }
//            physConn.setAutoCommit(localAutoCommitMode);
//
            return XA_OK;
//        } catch (SQLException ex) {
//            throw new PGXAException(GT.tr("Error preparing transaction"), ex, XAException.XAER_RMERR);
//        }
    }

    /**
     * Preconditions:
     * 1. flag must be one of TMSTARTRSCAN, TMENDRSCAN, TMNOFLAGS or TMSTARTTRSCAN | TMENDRSCAN
     * 2. if flag isn't TMSTARTRSCAN or TMSTARTRSCAN | TMENDRSCAN, a recovery scan must be in progress
     *
     * Postconditions:
     * 1. list of prepared xids is returned
     * 
     * @param flag
     * @return
     * @throws XAException 
     */
    public Xid[] recover(int flag) throws XAException {
        // Check preconditions
        if (flag != TMSTARTRSCAN && flag != TMENDRSCAN && flag != TMNOFLAGS && flag != (TMSTARTRSCAN | TMENDRSCAN)) {
            throw new PGXAException(GT.tr("Invalid flag"), XAException.XAER_INVAL);
        }

        // We don't check for precondition 2, because we would have to add some additional state in
        // this object to keep track of recovery scans.

//        // All clear. We return all the xids in the first TMSTARTRSCAN call, and always return
//        // an empty array otherwise.
//        if ((flag & TMSTARTRSCAN) == 0) {
            return new Xid[0];
//        } else {
//            try {
//                Statement stmt = physConn.createStatement();
//                try {
//                    // If this connection is simultaneously used for a transaction,
//                    // this query gets executed inside that transaction. It's OK,
//                    // except if the transaction is in abort-only state and the
//                    // backed refuses to process new queries. Hopefully not a problem
//                    // in practise.
//                    ResultSet rs = stmt.executeQuery("SELECT gid FROM pg_prepared_xacts");
//                    LinkedList l = new LinkedList();
//                    while (rs.next()) {
//                        Xid recoveredXid = RecoveredXid.stringToXid(rs.getString(1));
//                        if (recoveredXid != null) {
//                            l.add(recoveredXid);
//                        }
//                    }
//                    rs.close();
//
//                    return (Xid[]) l.toArray(new Xid[l.size()]);
//                } finally {
//                    stmt.close();
//                }
//            } catch (SQLException ex) {
//                throw new PGXAException(GT.tr("Error during recover"), ex, XAException.XAER_RMERR);
//            }
//        }
    }

    /**
     * 
     * @param xid
     * @throws XAException 
     */
    public void rollback(Xid xid) throws XAException {
        // TODO: Find the physical connection servicing the xid.
        // If it's prepared (already) then rollback prepared.
        // otherwise, just one-phase rollback.
        
//        try
//        {
//            if (currentXid != null && xid.equals(currentXid))
//            {
//                state = STATE_IDLE;
//                currentXid = null;
//                physConn.rollback();
//                physConn.setAutoCommit(localAutoCommitMode);
//            }
//            else
//            {
//                String s = RecoveredXid.xidToString(xid);
//
//                physConn.setAutoCommit(true);
//                Statement stmt = physConn.createStatement();
//                try
//                {
//                    stmt.executeUpdate("ROLLBACK PREPARED '" + s + "'");
//                }
//                finally
//                {
//                    stmt.close();
//                }
//            }
//        }
//        catch (SQLException ex)
//        {
//            throw new PGXAException(GT.tr("Error rolling back prepared transaction"), ex, XAException.XAER_RMERR);
//        }
    }

    /**
     * Not supported for the PostgreSQL XA Implementation.
     * 
     * @return
     * @throws XAException 
     */
    public int getTransactionTimeout() throws XAException {
        return 0; // We don't support this.
    }
    
    /**
     * Not supported for the PostgreSQL XA Implementation.
     * 
     * @param seconds
     * @return
     * @throws XAException 
     */
    public boolean setTransactionTimeout(int seconds) throws XAException {
        return false; // We don't support this.
    }
    
    
    
    
    
    
//
//    /**
//     * Preconditions:
//     * 1. flags must be one of TMNOFLAGS, TMRESUME or TMJOIN
//     * 2. xid != null
//     * 3. connection must not be associated with a transaction
//     * 4. the TM hasn't seen the xid before
//     *
//     * Implementation deficiency preconditions:
//     * 1. TMRESUME not supported.
//     * 2. if flags is TMJOIN, we must be in ended state,
//     *    and xid must be the current transaction
//     * 3. unless flags is TMJOIN, previous transaction using the 
//     *    connection must be committed or prepared or rolled back
//     * 
//     * Postconditions:
//     * 1. Connection is associated with the transaction
//     */
//    public void start(Xid xid, int flags) throws XAException {
////        if (logger.logDebug())
////            debug("starting transaction xid = " + xid);
//
//        // Check preconditions
//        if (flags != XAResource.TMNOFLAGS && flags != XAResource.TMRESUME && flags != XAResource.TMJOIN)
//            throw new PGXAException(GT.tr("Invalid flags"), XAException.XAER_INVAL);
//
//        if (xid == null)
//            throw new PGXAException(GT.tr("xid must not be null"), XAException.XAER_INVAL);
//
////        if (state == STATE_ACTIVE)
////            throw new PGXAException(GT.tr("Connection is busy with another transaction"), XAException.XAER_PROTO);
//
//// TODO: 
//        // We can't check precondition 4 easily, so we don't. Duplicate xid will be catched in prepare phase.
//
//        // Check implementation deficiency preconditions
//        if (flags == TMRESUME)
//            throw new PGXAException(GT.tr("suspend/resume not implemented"), XAException.XAER_RMERR);
//
//        // It's ok to join an ended transaction. WebLogic does that.
//        if (flags == TMJOIN)
//        {
//            if (state != STATE_ENDED)
//                throw new PGXAException(GT.tr("Transaction interleaving not implemented"), XAException.XAER_RMERR);
//
//            if (!xid.equals(currentXid))
//                throw new PGXAException(GT.tr("Transaction interleaving not implemented"), XAException.XAER_RMERR);
//        } else if(state == STATE_ENDED)
//            throw new PGXAException(GT.tr("Transaction interleaving not implemented"), XAException.XAER_RMERR);
//
//        try
//        {
//            localAutoCommitMode = physConn.getAutoCommit();
//            physConn.setAutoCommit(false);
//        }
//        catch (SQLException ex)
//        {
//            throw new PGXAException(GT.tr("Error disabling autocommit"), ex, XAException.XAER_RMERR);
//        }
//
//        // Preconditions are met, Associate connection with the transaction
//        state = STATE_ACTIVE;
//        currentXid = xid;
//    }
//
//    /**
//     * Preconditions:
//     * 1. Flags is one of TMSUCCESS, TMFAIL, TMSUSPEND
//     * 2. xid != null
//     * 3. Connection is associated with transaction xid
//     *
//     * Implementation deficiency preconditions:
//     * 1. Flags is not TMSUSPEND
//     * 
//     * Postconditions:
//     * 1. connection is disassociated from the transaction.
//     */
//    public void end(Xid xid, int flags) throws XAException {
//        if (logger.logDebug())
//            debug("ending transaction xid = " + xid);
//
//        // Check preconditions
//
//        if (flags != XAResource.TMSUSPEND && flags != XAResource.TMFAIL && flags != XAResource.TMSUCCESS)
//            throw new PGXAException(GT.tr("Invalid flags"), XAException.XAER_INVAL);
//
//        if (xid == null)
//            throw new PGXAException(GT.tr("xid must not be null"), XAException.XAER_INVAL);
//
//        if (state != STATE_ACTIVE || !currentXid.equals(xid))
//            throw new PGXAException(GT.tr("tried to call end without corresponding start call"), XAException.XAER_PROTO);
//
//        // Check implementation deficiency preconditions
//        if (flags == XAResource.TMSUSPEND)
//            throw new PGXAException(GT.tr("suspend/resume not implemented"), XAException.XAER_RMERR);
//
//        // We ignore TMFAIL. It's just a hint to the RM. We could roll back immediately
//        // if TMFAIL was given.
//
//        // All clear. We don't have any real work to do.
//        state = STATE_ENDED;
//    }
//
//    /**
//     * Preconditions:
//     * 1. xid != null
//     * 2. xid is in ended state
//     *
//     * Implementation deficiency preconditions:
//     * 1. xid was associated with this connection
//     * 
//     * Postconditions:
//     * 1. Transaction is prepared
//     */
//    public int prepare(Xid xid) throws XAException {
//        if (logger.logDebug())
//            debug("preparing transaction xid = " + xid);
//
//        // Check preconditions
//        if (!currentXid.equals(xid))
//        {
//            throw new PGXAException(GT.tr("Not implemented: Prepare must be issued using the same connection that started the transaction"),
//                                    XAException.XAER_RMERR);
//        }
//        if (state != STATE_ENDED)
//            throw new PGXAException(GT.tr("Prepare called before end"), XAException.XAER_INVAL);
//
//        state = STATE_IDLE;
//        currentXid = null;
//
//        if (!physConn.haveMinimumServerVersion("8.1"))
//            throw new PGXAException(GT.tr("Server versions prior to 8.1 do not support two-phase commit."), XAException.XAER_RMERR);
//
//        try
//        {
//            String s = RecoveredXid.xidToString(xid);
//
//            Statement stmt = physConn.createStatement();
//            try
//            {
//                stmt.executeUpdate("PREPARE TRANSACTION '" + s + "'");
//            }
//            finally
//            {
//                stmt.close();
//            }
//            physConn.setAutoCommit(localAutoCommitMode);
//
//            return XA_OK;
//        }
//        catch (SQLException ex)
//        {
//            throw new PGXAException(GT.tr("Error preparing transaction"), ex, XAException.XAER_RMERR);
//        }
//    }
//
//    /**
//     * Preconditions:
//     * 1. flag must be one of TMSTARTRSCAN, TMENDRSCAN, TMNOFLAGS or TMSTARTTRSCAN | TMENDRSCAN
//     * 2. if flag isn't TMSTARTRSCAN or TMSTARTRSCAN | TMENDRSCAN, a recovery scan must be in progress
//     *
//     * Postconditions:
//     * 1. list of prepared xids is returned
//     */
//    public Xid[] recover(int flag) throws XAException {
//        // Check preconditions
//        if (flag != TMSTARTRSCAN && flag != TMENDRSCAN && flag != TMNOFLAGS && flag != (TMSTARTRSCAN | TMENDRSCAN))
//            throw new PGXAException(GT.tr("Invalid flag"), XAException.XAER_INVAL);
//
//        // We don't check for precondition 2, because we would have to add some additional state in
//        // this object to keep track of recovery scans.
//
//        // All clear. We return all the xids in the first TMSTARTRSCAN call, and always return
//        // an empty array otherwise.
//        if ((flag & TMSTARTRSCAN) == 0)
//            return new Xid[0];
//        else
//        {
//            try
//            {
//                Statement stmt = physConn.createStatement();
//                try
//                {
//                    // If this connection is simultaneously used for a transaction,
//                    // this query gets executed inside that transaction. It's OK,
//                    // except if the transaction is in abort-only state and the
//                    // backed refuses to process new queries. Hopefully not a problem
//                    // in practise.
//                    ResultSet rs = stmt.executeQuery("SELECT gid FROM pg_prepared_xacts");
//                    LinkedList l = new LinkedList();
//                    while (rs.next())
//                    {
//                        Xid recoveredXid = RecoveredXid.stringToXid(rs.getString(1));
//                        if (recoveredXid != null)
//                            l.add(recoveredXid);
//                    }
//                    rs.close();
//
//                    return (Xid[]) l.toArray(new Xid[l.size()]);
//                }
//                finally
//                {
//                    stmt.close();
//                }
//            }
//            catch (SQLException ex)
//            {
//                throw new PGXAException(GT.tr("Error during recover"), ex, XAException.XAER_RMERR);
//            }
//        }
//    }
//
//    /**
//     * Preconditions:
//     * 1. xid is known to the RM or it's in prepared state
//     *
//     * Implementation deficiency preconditions:
//     * 1. xid must be associated with this connection if it's not in prepared state.
//     * 
//     * Postconditions:
//     * 1. Transaction is rolled back and disassociated from connection
//     */
//    public void rollback(Xid xid) throws XAException {
//        if (logger.logDebug())
//            debug("rolling back xid = " + xid);
//
//        // We don't explicitly check precondition 1.
//
//        try
//        {
//            if (currentXid != null && xid.equals(currentXid))
//            {
//                state = STATE_IDLE;
//                currentXid = null;
//                physConn.rollback();
//                physConn.setAutoCommit(localAutoCommitMode);
//            }
//            else
//            {
//                String s = RecoveredXid.xidToString(xid);
//
//                physConn.setAutoCommit(true);
//                Statement stmt = physConn.createStatement();
//                try
//                {
//                    stmt.executeUpdate("ROLLBACK PREPARED '" + s + "'");
//                }
//                finally
//                {
//                    stmt.close();
//                }
//            }
//        }
//        catch (SQLException ex)
//        {
//            throw new PGXAException(GT.tr("Error rolling back prepared transaction"), ex, XAException.XAER_RMERR);
//        }
//    }
//
//    public void commit(Xid xid, boolean onePhase) throws XAException {
//        if (logger.logDebug())
//            debug("committing xid = " + xid + (onePhase ? " (one phase) " : " (two phase)"));
//
//        if (xid == null)
//            throw new PGXAException(GT.tr("xid must not be null"), XAException.XAER_INVAL);
//
//        if (onePhase)
//            commitOnePhase(xid);
//        else
//            commitPrepared(xid);
//    }
//
//    /**
//     * Preconditions:
//     * 1. xid must in ended state.
//     *
//     * Implementation deficiency preconditions:
//     * 1. this connection must have been used to run the transaction
//     * 
//     * Postconditions:
//     * 1. Transaction is committed
//     */
//    private void commitOnePhase(Xid xid) throws XAException {
//        try
//        {
//            // Check preconditions
//            if (currentXid == null || !currentXid.equals(xid))
//            {
//                // In fact, we don't know if xid is bogus, or if it just wasn't associated with this connection.
//                // Assume it's our fault.
//                throw new PGXAException(GT.tr("Not implemented: one-phase commit must be issued using the same connection that was used to start it"),
//                                        XAException.XAER_RMERR);
//            }
//            if (state != STATE_ENDED)
//                throw new PGXAException(GT.tr("commit called before end"), XAException.XAER_PROTO);
//
//            // Preconditions are met. Commit
//            state = STATE_IDLE;
//            currentXid = null;
//
//            physConn.commit();
//            physConn.setAutoCommit(localAutoCommitMode);
//        }
//        catch (SQLException ex)
//        {
//            throw new PGXAException(GT.tr("Error during one-phase commit"), ex, XAException.XAER_RMERR);
//        }
//    }
//
//    /**
//     * Preconditions:
//     * 1. xid must be in prepared state in the server
//     *
//     * Implementation deficiency preconditions:
//     * 1. Connection must be in idle state
//     * 
//     * Postconditions:
//     * 1. Transaction is committed
//     */
//    private void commitPrepared(Xid xid) throws XAException {
//        try
//        {
//            // Check preconditions. The connection mustn't be used for another
//            // other XA or local transaction, or the COMMIT PREPARED command
//            // would mess it up.
//            if (state != STATE_IDLE || physConn.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE)
//                throw new PGXAException(GT.tr("Not implemented: 2nd phase commit must be issued using an idle connection"),
//                                        XAException.XAER_RMERR);
//
//            String s = RecoveredXid.xidToString(xid);
//
//            localAutoCommitMode = physConn.getAutoCommit();
//            physConn.setAutoCommit(true);
//            Statement stmt = physConn.createStatement();
//            try
//            {
//                stmt.executeUpdate("COMMIT PREPARED '" + s + "'");
//            }
//            finally
//            {
//                stmt.close();
//                physConn.setAutoCommit(localAutoCommitMode);
//            }
//        }
//        catch (SQLException ex)
//        {
//            throw new PGXAException(GT.tr("Error committing prepared transaction"), ex, XAException.XAER_RMERR);
//        }
//    }
//
//    public boolean isSameRM(XAResource xares) throws XAException {
//        // This trivial implementation makes sure that the
//        // application server doesn't try to use another connection
//        // for prepare, commit and rollback commands.
//        return xares == this;
//    }
//
//    /**
//     * Does nothing, since we don't do heuristics, 
//     */
//    public void forget(Xid xid) throws XAException {
//        throw new PGXAException(GT.tr("Heuristic commit/rollback not supported"), XAException.XAER_NOTA);
//    }

}
