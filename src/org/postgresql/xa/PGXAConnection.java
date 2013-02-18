/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2011, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package org.postgresql.xa;

import java.sql.*;
import java.util.LinkedList;
import javax.sql.*;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import org.postgresql.ds.PGPooledConnection;
import org.postgresql.util.GT;

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
    
    private String user;
    private PGXADataSource dataSource;
    private boolean inXaTx;
    
    /*
     * When an XA transaction is started, we put the logical connection
     * into non-autocommit mode. The old setting is saved in
     * localAutoCommitMode, so that we can restore it when the XA transaction
     * ends and the connection returns into local transaction mode.
     */
    private boolean localTxAutoCommitMode = true;
    
    protected PGXAConnection(final String user, final Connection logicalConnection, PGXADataSource dataSource) {
        super(logicalConnection, true, true);
        this.user = user;
        this.dataSource = dataSource;
        this.inXaTx = false;
    }
    
    /**
     * Returns a handle to the logical connection (we are pooled, remember)
     * 
     * @return
     * @throws SQLException 
     */
    @Override
    public Connection getConnection() throws SQLException {
        return super.getConnection();
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
     * Preconditions:
     * 1. flags must be one of TMNOFLAGS, TMRESUME or TMJOIN
     * 2. xid != null
     * 3. Logical connection must not be associated with another backend
     * 4. if TMNOFLAGS, the RM hasn't seen the xid before.
     *
     * Postconditions:
     * 1. Connection is associated with the transaction
     * 
     * @param xid
     * @param flags
     * @throws XAException 
     */
    public void start(Xid xid, int flags) throws XAException {
        if (xid == null) {
            throw new PGXAException(GT.tr("xid must not be null"), XAException.XAER_INVAL);
        }
        
        try {
            this.localTxAutoCommitMode = getBackingConnection().getAutoCommit();
            getBackingConnection().setAutoCommit(false);
            inXaTx = true;
        } catch (SQLException sqle) {
            throw new PGXAException(GT.tr("Error disabling autocommit"), sqle, XAException.XAER_RMERR);
        }
        
        switch (flags) {
            case XAResource.TMNOFLAGS:
                try {
                    dataSource.start(this, xid);
                } catch (XAException xae) {
                    throw new PGXAException(GT.tr(xae.getMessage()), xae, XAException.XAER_DUPID);
                }
                break;
            case XAResource.TMRESUME:
                // Associate the logicalConnectionId with an existing, pegged physical backend in the suspended state.
                try {
                    dataSource.resume(this, xid);
                } catch (XAException xae) {
                    throw new PGXAException(GT.tr(xae.getMessage()), xae, XAException.XAER_INVAL);
                }
                
                break;
            case XAResource.TMJOIN: 
                // Associate the logicalConnectionId with an existing, pegged physical backend not in the suspended state.
                try {
                    dataSource.join(this, xid);
                } catch (XAException xae) {
                    throw new PGXAException(GT.tr(xae.getMessage()), xae, XAException.XAER_INVAL);
                }
                
                break;
            default:
                throw new PGXAException(GT.tr("Invalid flags"), XAException.XAER_INVAL);
        }
    }
    
    /**
     * Preconditions:
     * 1. Flags is one of TMSUCCESS, TMFAIL, TMSUSPEND
     * 2. xid != null
     * 3. Logical Connection is associated with physical backend servicing xid
     *
     * Postconditions:
     * 1. connection is disassociated from the transaction.
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

        // TMSUSPEND, TMSUCCESS, TMFAIL are more or less hints to this resource manager. 
        // We don't _have_ to do anything special to handle them, although we could rollback immediately (if we wanted) 
        // in the case of of TMFAIL.
        
        if (flags == XAResource.TMSUSPEND) {
            dataSource.suspend(this, xid);
        }
        dataSource.end(this, xid);
        inXaTx = false;
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
        dataSource.forget(xid);
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

        dataSource.commit(this, xid, onePhase);
        if (onePhase && !inXaTx) {
            try {
                getBackingConnection().setAutoCommit(localTxAutoCommitMode);
            } catch (SQLException sqle) {
                throw new PGXAException(GT.tr("Failed to restore local transaction auto commit mode."), sqle, XAException.XAER_RMERR);
            }
        }
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
     * Preconditions:
     * 1. xid != null
     * 2. A physical connection is pegged to the Xid, is not suspended, and is not associated to a logical connection.
     *
     * Postconditions:
     * 1. Transaction is prepared, and the physical backend which it was associated to is left unsuspended
     * 
     * @param xid
     * @return
     * @throws XAException 
     */
    public int prepare(Xid xid) throws XAException {
        try {
            int ret = dataSource.prepare(xid);
            getBackingConnection().setAutoCommit(localTxAutoCommitMode);
            return ret;
        } catch (Exception ex) {
            throw new PGXAException(GT.tr(ex.getMessage()), ex, XAException.XAER_RMERR);
        }
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
        
        // All clear. We return all the xids in the first TMSTARTRSCAN call, and always return
        // an empty array otherwise.
        if ((flag & TMSTARTRSCAN) == 0) {
            return new Xid[0];
        } else {
            ResultSet rs = null;
            Statement stmt = null;
            try {
                // TODO: Reconcile this method against the JTA spec. It seems very incorrect at this point, although I don't believe
                // you can hold a cursor in a prepared transaction, so this may be a case of needing an extra 'control' connection.
                
                stmt = dataSource.getPhysicalConnection(this, true).getConnection().createStatement();
                
                // If this connection is simultaneously used for a transaction,
                // this query gets executed inside that transaction. It's OK,
                // except if the transaction is in abort-only state and the
                // backed refuses to process new queries. Hopefully not a problem
                // in practise.
                rs = stmt.executeQuery("SELECT gid FROM pg_prepared_xacts");
                LinkedList<Xid> l = new LinkedList<Xid>();
                while (rs.next()) {
                    Xid recoveredXid = RecoveredXid.stringToXid(rs.getString(1));
                    if (recoveredXid != null) {
                        l.add(recoveredXid);
                    }
                }

                return l.toArray(new Xid[l.size()]);
            } catch (SQLException ex) {
                throw new PGXAException(GT.tr("Error during recover"), ex, XAException.XAER_RMERR);
            } finally {
                if (rs != null) {
                    try {
                        rs.close();
                    } catch (SQLException sqle) {
                        throw new PGXAException(GT.tr("Error during recover"), sqle, XAException.XAER_RMERR);
                    }
                }
                
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (SQLException sqle) { // Fail quietly.
                        throw new PGXAException(GT.tr("Error during recover"), sqle, XAException.XAER_RMERR);
                    }
                }
            }
        }
    }

    /**
     * 
     * @param xid
     * @throws XAException 
     */
    public void rollback(Xid xid) throws XAException {
        dataSource.rollback(this, xid);
        try {
            getBackingConnection().setAutoCommit(localTxAutoCommitMode);
        } catch (SQLException sqle) {
            throw new PGXAException(GT.tr("Failed to restore local transaction auto commit mode."), sqle, XAException.XAER_RMERR);
        }
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

    /**
     * Used by the PGXADataSource to make sure we're getting a proper physical connection.
     * 
     * @return 
     */
    String getUser() {
        return user;
    }
}
