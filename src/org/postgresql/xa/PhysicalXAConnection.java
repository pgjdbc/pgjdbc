/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2013, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package org.postgresql.xa;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import javax.transaction.xa.Xid;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Logger;
import org.postgresql.core.ProtocolConnection;
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
    private int backendPid;
    
    private boolean localAutoCommit;
    
    private Xid associatedXid;
    private boolean suspended;
    
    /** Private lock for managing association / disassociation critical sections. */
    private final ReentrantLock managementLock = new ReentrantLock();
    private List<PGXAConnection> logicalConnections = new ArrayList<PGXAConnection>(2);

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
    PhysicalXAConnection(final BaseConnection physicalConn, final String user, final String password) throws SQLException {
        this.connection = physicalConn;
        this.user = user;
        this.password = password;
        this.associatedXid = null;
        this.suspended = false;
        this.backendPid = physicalConn.getBackendPID();
        this.logger = physicalConn.getLogger();
        this.localAutoCommit = physicalConn.getAutoCommit();
        if (logger.logDebug()) {
            logger.debug(GT.tr("[{0}] - {1} instantiated", new Object[]{backendPid, PhysicalXAConnection.class.getName()}));
        }
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

    ReentrantLock getManagementLock() {
        return managementLock;
    }
    
    /**
     * Associates the logical connection on the given thread to do work on behalf of the given xid.
     * 
     * @param logicalConnection
     * @param xid
     * 
     * @throws IllegalStateException if the connection is currently associated to a xid other than the supplied xid.
     */
    boolean associate(final PGXAConnection logicalConnection, final Xid xid) throws IllegalStateException {
        managementLock.lock();
        try {
            // Sanity checks.
            if (associatedXid != null) {
                if (xid == null) {
                    if (logger.logDebug()) {
                        logger.debug(GT.tr("Attempted to associate a phsycial connection for local TX mode which is already servicing distributed transaction xid: {0}", 
                                                              RecoveredXid.xidToString(associatedXid)));
                    }
                    return false;
                } else if (!associatedXid.equals(xid)) {
                    if (logger.logDebug()) {
                        logger.debug(GT.tr("Attempted to associate a physical connection to xid [{0}], which differs from it's current xid [{1}].",
                                                              new Object[] {RecoveredXid.xidToString(xid), RecoveredXid.xidToString(associatedXid)}));
                    }
                    return false;
                }
            }
            
            boolean available = 
                            (logicalConnections.contains(logicalConnection) || logicalConnections.isEmpty()) || // Already servicing this logical connection, or not servicing any at all.
                            (associatedXid != null && associatedXid.equals(xid)) || // same xid
                    
                            // No xid (local TX mode), no other logical connection, and it's the same user.
                            (associatedXid == null && 
                             logicalConnections.isEmpty() && 
                             logicalConnection.getUser().equals(user));
            
            if (available) {
                // If we're associating to an xid, turn off autocommit.
                if (this.associatedXid == null && xid != null) {
                    this.localAutoCommit = connection.getAutoCommit();
                    connection.setAutoCommit(false);
                }
                this.associatedXid = xid;
                this.logicalConnections.add(logicalConnection);
                logicalConnection.setPhysicalXAConnection(this);

                if (logger.logDebug()) {
                    logger.debug(GT.tr("[{0}] - associated to xid: {2}", new Object[]{backendPid, RecoveredXid.xidToString(xid)}));
                }
            }
            
            return available;
        } catch (SQLException sqle) {
            if (logger.logDebug()) {
                logger.debug(GT.tr("Could not modify autocommit state for physical connection."), sqle);
            }
            
            return false;
        } finally {
            managementLock.unlock();
        }
    }
    
    
    boolean isCloseable(final PGXAConnection logicalConnection) {
        return logicalConnections.isEmpty() && 
               associatedXid == null && 
               connection.getTransactionState() != ProtocolConnection.TRANSACTION_OPEN &&
               user.equals(logicalConnection.getUser());
    }
    
    
    /**
     * Disassociates the logical connection on the given thread from this physical connection.
     * 
     * @param logicalConnection 
     * @param thread
     * @param xid If not null, removes the xid association from this physical connection.
     * 
     * @throws IllegalStateException 
     */
    void disassociate(final PGXAConnection logicalConnection, final Xid xid) throws IllegalStateException {
        managementLock.lock();
        try {
            if (logicalConnection != null) {
                if (!logicalConnections.remove(logicalConnection)) {
                    throw new IllegalStateException(GT.tr("Attempted to dissassociate a logical connection not associated to this physical connection."));
                }
                logicalConnection.setPhysicalXAConnection(null);
            }
            
            // Clean up the xid...
            if (xid != null) {
                if (associatedXid == null) {
                    throw new IllegalStateException(GT.tr("Attempted to disassociate xid [{0}] from physical connection not servicing any xid.", 
                                                          RecoveredXid.xidToString(xid)));
                } else if (!xid.equals(associatedXid)) {
                    throw new IllegalStateException(GT.tr("Attempted to disassociate xid [{0}] from physical connection servicing xid [{1}].",
                                                          new Object[] {RecoveredXid.xidToString(xid), RecoveredXid.xidToString(associatedXid)}));
                }

                // Restore the autocommit.
                connection.setAutoCommit(localAutoCommit);
                associatedXid = null;
                suspended = false;
                
            }
        } catch (SQLException sqle) {
            if (logger.logDebug()) {
                logger.debug(GT.tr("Could not restore autocommit mode for physical conneciton."), sqle);
            }
        } finally {
            managementLock.unlock();
        }
    }
    
    void disassociate() {
        managementLock.lock();
        try {
            // Clean up the logical connections.
            PGXAConnection logical = null;
            for (int i = 0; i < logicalConnections.size(); i++) {
                logical = logicalConnections.get(i);
                disassociate(logical, null);
            }
            // Disassociate any remaining xid.
            disassociate(null, associatedXid);
        } finally {
            managementLock.unlock();
        }
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