/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2013, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package org.postgresql.xa;

import java.sql.SQLException;
import javax.transaction.xa.Xid;
import org.postgresql.core.BaseConnection;

/**
 * Wrapper to track the state of a BaseConnection in use as a physical backend for PGXAConnections.
 * 
 * @author Bryan Varner (bvarner@polarislabs.com)
 */
class PhysicalXAConnection {
    private BaseConnection connection;
    private String user;
    private String password;
    private Xid associatedXid;
    private boolean suspended;
    private int backendPid;
    private boolean originalAutoCommit;

    PhysicalXAConnection(BaseConnection physicalConn, String user, String password) {
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
    }

    BaseConnection getConnection() {
        return connection;
    }

    Xid getAssociatedXid() {
        return associatedXid;
    }

    void setAssociatedXid(final Xid xid) {
        try {
            if (associatedXid != null && xid == null) { // restore the autocommit state.
                connection.setAutoCommit(originalAutoCommit);
            } else if (associatedXid == null && xid != null) { // cache the autocommit state.
                this.originalAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
            }
        } catch (SQLException sqle) {
            // TODO: Log that we had a problem here.
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

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final PhysicalXAConnection other = (PhysicalXAConnection) obj;
        if (this.backendPid != other.backendPid) {
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