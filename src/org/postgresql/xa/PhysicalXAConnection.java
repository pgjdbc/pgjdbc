/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2013, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package org.postgresql.xa;

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

    public PhysicalXAConnection(BaseConnection physicalConn) {
        connection = physicalConn;
        associatedXid = null;
        suspended = false;
        backendPid = physicalConn.getBackendPID();
    }

    public BaseConnection getConnection() {
        return connection;
    }

    public Xid getAssociatedXid() {
        return associatedXid;
    }

    public void setAssociatedXid(final Xid associatedXid) {
        this.associatedXid = associatedXid;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public void setSuspended(final boolean suspended) {
        this.suspended = suspended;
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