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
public class PhysicalXAConnection {
    private BaseConnection connection;
    private Xid associatedXid;
    private boolean suspended;
    private int backendPid;
    private boolean originalAutoCommit;

    public PhysicalXAConnection(BaseConnection physicalConn) {
        connection = physicalConn;
        associatedXid = null;
        suspended = false;
        backendPid = physicalConn.getBackendPID();
        try {
            originalAutoCommit = physicalConn.getAutoCommit();
        } catch (SQLException sqle) {
            originalAutoCommit = true; // Default to true, if we can't get the real value.
        }
    }

    public BaseConnection getConnection() {
        return connection;
    }

    public Xid getAssociatedXid() {
        return associatedXid;
    }

    public void setAssociatedXid(final Xid xid) {
        try {
            if (associatedXid != null && xid == null) { // restore the autocommit state.
                connection.setAutoCommit(originalAutoCommit);
            } else if (associatedXid == null && xid != null) { // cache the autocommit state.
                originalAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
            }
        } catch (SQLException sqle) {
            // TODO: Log that we had a problem here.
        }
        
        this.associatedXid = xid;
        this.suspended = false;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public void markSuspended() {
        this.suspended = true;
    }
    
    public int getBackendPID() {
        return backendPid;
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