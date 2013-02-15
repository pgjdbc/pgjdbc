/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2013, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package org.postgresql.xa;

import java.sql.SQLException;
import java.text.MessageFormat;
import javax.transaction.xa.Xid;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Logger;
import org.postgresql.util.GT;

/**
 * Wrapper to track the state of a BaseConnection in use as a physical backend for PGXAConnections.
 * 
 * @author Bryan Varner (bvarner@polarislabs.com)
 */
class PhysicalXAConnection {

    private final Logger logger;

    private BaseConnection connection;
    private String user;
    private String password;
    private Xid associatedXid;
    private boolean suspended;
    private int backendPid;
    private boolean originalAutoCommit;

    /**
     * Construct a PhysicalXAConnection.  After construction, this connection will have no associated xid,
     * will be set to not suspended, and will store the original auto commit value in order to restore it after a
     * transaction is complete if one is started.  Original auto-commit will default to true if there is a problem
     * retrieving its current value.
     *
     * @param physicalConn The BaseConnection to wrap
     * @param user The username credential for the connection
     * @param password The password for the connection
     */
    PhysicalXAConnection(final BaseConnection physicalConn, final String user, final String password) {
        this.connection = physicalConn;
        this.user = user;
        this.password = password;
        this.associatedXid = null;
        this.suspended = false;
        this.backendPid = physicalConn.getBackendPID();
        try {
            this.originalAutoCommit = physicalConn.getAutoCommit();
        } catch (SQLException sqle) {
            this.originalAutoCommit = true; // Default to true, if we can't get the real value.
        }
        logger = physicalConn.getLogger();
        logger.debug(GT.tr("[{0}] - {1} instantiated with autoCommit: {2}", new Object[]{backendPid, PhysicalXAConnection.class.getName(), originalAutoCommit}));
    }

    /**
     * @return The wrapped BaseConnection
     */
    BaseConnection getConnection() {
        return connection;
    }

    /**
     * @return The transaction id associated to this connection
     */
    Xid getAssociatedXid() {
        return associatedXid;
    }

    /**
     * Sets the transaction id associated to this connection.
     *
     * If the provided transaction id is null and there is no currently associated transaction id, restore the
     * auto-commit state.
     *
     * If the provided transaction id is not null, and there is no currently associated transaction id, cache the
     * auto-commit state.
     *
     * If all goes well, the transaction id will be associated to this connection and the connection will be set to
     * not suspended.
     *
     * @param xid the transaction id to associate to this connection
     */
    void setAssociatedXid(final Xid xid) {
        try {
            if (associatedXid != null && xid == null) { // restore the autocommit state.
                connection.setAutoCommit(originalAutoCommit);
                logger.debug(GT.tr("[{0}] - Restoring original auto commit: {1}", new Object[]{backendPid, originalAutoCommit}));
            } else if (associatedXid == null && xid != null) { // cache the autocommit state.
                this.originalAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                logger.debug(GT.tr("[{0}] - Caching original auto commit: {1}", new Object[]{backendPid, originalAutoCommit}));
            }
        } catch (SQLException sqle) {
            logger.debug(GT.tr("[{0}] - There was a problem associating to xid: {1}", new Object[] {backendPid, RecoveredXid.xidToString(xid)}), sqle);
        }

        this.associatedXid = xid;
        this.suspended = false;
    }

    boolean isSuspended() {
        return suspended;
    }

    void markSuspended() {
        this.suspended = true;
    }
    
    int getBackendPID() {
        return backendPid;
    }
    
    String getUser() {
        return user;
    }
    
    String getPassword() {
        return password;
    }

    /**
     * @param obj The object to compare
     * @return false if the provided object is null, not the same class, or does not have the same backend PID
     *  true otherwise.
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null || getClass() != obj.getClass() || this.backendPid != ((PhysicalXAConnection)obj).backendPid) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 3;
        hash = 13 * hash + this.backendPid;
        return hash;
    }
}