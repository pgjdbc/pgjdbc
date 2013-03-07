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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.transaction.xa.Xid;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Logger;
import org.postgresql.core.ProtocolConnection;
import org.postgresql.util.GT;

/**
 * Physical XAConnection represents a physical connection to the backend PG server, and exposes methods allowing it to be associated
 * to logical connections for servicing invocations on logical Connection objects.
 * 
 * @author Bryan Varner (bvarner@polarislabs.com)
 */
class PhysicalXAConnection {

    private final Logger logger;
    
    private BaseConnection connection;
    private String user;
    private int backendPid;
    
    private boolean localAutoCommit;
    
    private Xid associatedXid;
    private boolean suspended;
    
    /** Private lock for managing association / disassociation critical sections. */
    private final ReentrantLock associationLock = new ReentrantLock();
    private List<PGXAConnection> logicalConnections = new ArrayList<PGXAConnection>(2);

    /**
     * Construct a PhysicalXAConnection.  After construction, this connection will have no associated xid,
     * will be set to not suspended, and will store the original auto commit value in order to restore it after a
     * transaction is complete if one is started.  Original auto-commit will default to true if there is a problem
     * retrieving its current value.
     *
     * @param physicalConn The BaseConnection to wrap
     * @param user The username credential for the connection
     */
    PhysicalXAConnection(final BaseConnection physicalConn, final String user) throws SQLException {
        this.connection = physicalConn;
        this.user = user;
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

    /**
     * Exposes the association lock for this physical connection, allowing collaborators to lock it's association state while 
     * performing operations on the physical connection.
     * 
     * @return 
     */
    ReentrantLock getAssociationLock() {
        return associationLock;
    }
    
    void close() throws SQLException {
        disassociate();
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
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
        try {
            if (associationLock.tryLock(0, TimeUnit.SECONDS)) {
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

                    // Check availability. I should probably be punished for writing a conditional like this.
                    if (!connection.isClosed() && // not closed AND
                        logicalConnection.getUser().equals(user) && // Same user AND
                        (logicalConnections.isEmpty() || logicalConnections.contains(logicalConnection)) && // No association or associated to this logical AND

                        ((associatedXid != null && associatedXid.equals(xid)) || // Already associated to the same Xid OR 
                         (associatedXid == null &&  // Local TX mode AND either
                            (connection.getTransactionState() == ProtocolConnection.TRANSACTION_IDLE || // No TX in progress OR
                             (isOnlyLogicalAssociation(logicalConnection) && xid == null)))) // (Possibly in-progress, idle, or rolledback)
                                                                                              // but nevertheless already associated to this
                                                                                              // logical, AND not requesting a xid.
                    ) {
                        // If we're associating to an xid, turn off autocommit.
                        if (this.associatedXid == null && xid != null) {
                            this.localAutoCommit = connection.getAutoCommit();
                            connection.setAutoCommit(false);
                        }
                        this.associatedXid = xid;
                        if (!this.logicalConnections.contains(logicalConnection)) {
                            this.logicalConnections.add(logicalConnection);
                        }
                        logicalConnection.setPhysicalXAConnection(this);

                        if (logger.logDebug()) {
                            logger.debug(GT.tr("[{0}] - associated to xid: {2}", new Object[]{backendPid, RecoveredXid.xidToString(xid)}));
                        }
                        return true;
                    }
                } catch (SQLException sqle) {
                    if (logger.logDebug()) {
                        logger.debug(GT.tr("Could not modify autocommit state for physical connection."), sqle);
                    }
                } finally {
                    associationLock.unlock();
                }
            }
        } catch (InterruptedException ie) { 
            return false;
        }
        return false;
    }
    
    /**
     * Determines if the physical connection is in a closeable state if the given logical connection is being closed.
     * 
     * @param logicalConnection
     * 
     * @return true if this physical connection may be cleaned up, false if not.
     */
    boolean isCloseable(final PGXAConnection logicalConnection) {
        try {
            if (associationLock.tryLock(0, TimeUnit.SECONDS)) {
                boolean closeable = (logicalConnections.isEmpty() || isOnlyLogicalAssociation(logicalConnection)) &&
                                     associatedXid == null && 
                                     user.equals(logicalConnection.getUser());
                if (!closeable) {
                    associationLock.unlock();
                }
                return closeable;
            }
        } catch (InterruptedException ie) {
            return false;
        }
        return false;
    }
    
    /**
     * Determines if the given logical connection is the only associated connection we're servicing.
     * 
     * @param logicalConnection
     * @return true if this physical connection is servicing only the given logical connection.
     */
    private boolean isOnlyLogicalAssociation(final PGXAConnection logicalConnection) {
        return (logicalConnections.size() == 1 && logicalConnections.contains(logicalConnection));
    }
    
    
    /**
     * Disassociates the logical connection or xid from this physical connection.
     * 
     * @param logicalConnection If not null, removes the specified logicalConnection from this physical connection.
     * @param xid If not null, removes the xid association from this physical connection.
     * 
     * @throws IllegalStateException 
     */
    void disassociate(final PGXAConnection logicalConnection, final Xid xid) throws IllegalStateException {
        associationLock.lock(); // wait for it.
        try {
            if (logicalConnection != null) {
                if (!logicalConnections.remove(logicalConnection)) {
                    throw new IllegalStateException(GT.tr("Attempted to disassociate a logical connection not associated to this physical connection."));
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
            associationLock.unlock();
        }
    }
    
    /**
     * Disassociates the logical connection from this physical connection.
     * 
     * @param logicalConnection If not null, removes the specified logicalConnection from this physical connection.
     * 
     * @throws IllegalStateException 
     */
    void disassociate(final PGXAConnection logicalConnection) throws IllegalStateException {
        disassociate(logicalConnection, null);
    }
    
    /**
     * Disassociates the xid from this physical connection.
     * 
     * @param logicalConnection If not null, removes the specified logicalConnection from this physical connection.
     * 
     * @throws IllegalStateException 
     */
    void disassociate(final Xid xid) {
        disassociate(null, xid);
    }
    
    /**
     * Disassociates all logical connections and any associated xid from this connection.
     */
    void disassociate() {
        associationLock.lock();
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
            associationLock.unlock();
        }
    }
    
    /**
     * @return true if this connection backs a suspended XAResource.
     */
    boolean isSuspended() {
        return suspended;
    }

    /**
     * Marks this physical connection as backing a suspended XAResource.
     */
    void markSuspended() {
        this.suspended = true;
    }
    
    /**
     * Gets the PID from the underlying PGConnection
     * 
     * @return 
     */
    int getBackendPID() {
        return backendPid;
    }
    
    /**
     * Gets the user this connection was created with.
     * 
     * @return 
     */
    String getUser() {
        return user;
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