/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2013, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package org.postgresql.xa;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.StringRefAddr;
import javax.sql.XAConnection;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Logger;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

/**
 * An XADataSource implementation which complies with the JTA spec for interleaving, resource sharing, etc.
 * 
 * The postgresql implementation of XADataSource has the following configuration options, which can be used to configure
 * support for XA interleaving.
 * <ul>
 * <li>xaAcquireTimeout - Sets the amount of time to wait for physical connections to be released 
 *         from servicing a logical connection before opening a new physical connection. Setting this to 0 will disable support for
 *         creating more physical connections than logical connections which have been created by the data source.</li>
 * <li>xaWaitStep - Sets the max amount of time to wait on a synchronizer monitor before re-checking for a physical connection.</li>
 * </ul>
 * 
 * @author bvarner
 */
public class PGXADataSource extends AbstractPGXADataSource {

    private final Logger logger = new Logger();

    /**
     * Collection of physicalConnections to the backend data source.
     * 
     * Invocations on the Logical connection are routed to an available 
     * backend connection via a Proxy InvocationHandler.
     */
    final List<PhysicalXAConnection> physicalConnections =
            Collections.synchronizedList(new ArrayList<PhysicalXAConnection>());

    /**
     * Time to wait for a physical connection to become available before we create a new physical connection to service a logical.
     */
    private int xaAcquireTimeout = 50; // in milliseconds

    /**
     * Time to wait for a physical connection to become available before checking again.
     * Once we've waited up to acquirePhysicalTimeout, we'll create a new backend.
     */
    private int xaWaitStep = 10; // in milliseconds;

    /**
     * Protected method for returning new instances of PGXAConnection or subclasses.
     */
    protected PGXAConnection createPGXAConnection(String user, String password, Connection connection) {
        return new PGXAConnection(user, password, connection, this);
    }

    /**
     * Gets an XA-enabled connection to the PostgreSQL database.  The database is identified by the
     * DataSource properties serverName, databaseName, and portNumber. The user to
     * connect as is identified by the arguments user and password, which override
     * the DataSource properties by the same name.
     *
     * @return A valid database connection.
     * @throws SQLException
     *     Occurs when the database connection cannot be established.
     */
    public XAConnection getXAConnection(final String user, final String password) throws SQLException {
        allocatePhysicalConnection(user, password);
        
        // Create a new LogicalXAConnectionHandler proxy.
        LogicalXAConnectionHandler logicalHandler = new LogicalXAConnectionHandler();
        PGXAConnection logicalConnection = createPGXAConnection(
                user, password,
                (Connection)Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class[]{Connection.class, PGConnection.class},
                        logicalHandler));
        logicalHandler.setLogicalConnection(logicalConnection);
        
        // Make sure we have an associated physical connection.
        try {
            resolvePhysicalConnection(logicalConnection, null);
        } catch (PGXAException pgxa) {
            throw new SQLException(pgxa);
        }

        if (logger.logDebug()) {
            logger.debug(GT.tr("New logical connection handler proxy created"));
        }
        return logicalConnection;
    }
    
    public int getPhysicalConnectionCount() {
        return physicalConnections.size();
    }
    
    public int getAvailableConnections(final PGXAConnection logicalConnection) {
        int available = 0;
        synchronized(physicalConnections) {
            for (int i = 0; i < physicalConnections.size(); i++) {
                if (physicalConnections.get(i).isCloseable(logicalConnection)) {
                    available++;
                }
            }
        }
        return available;
    }

    void closePhysicalsMatching(final PGXAConnection logicalConnection) throws SQLException {
        // Disassociate the logical from a backend first.
        synchronized(physicalConnections) {
            // Prune the physical connections
            List<PhysicalXAConnection> closeable = new ArrayList<PhysicalXAConnection>();
            PhysicalXAConnection candidate = null;
            for (int i = 0; i < physicalConnections.size(); i++) {
                candidate = physicalConnections.get(i);
                if (candidate.isCloseable(logicalConnection)) {
                    closeable.add(candidate);
                }
            }

            if (logger.logDebug()) {
                logger.debug(GT.tr("Removing {0} closable physical connections out of {1} total physical connections.", new Object[]{closeable.size(), physicalConnections.size()}));
            }

            physicalConnections.removeAll(closeable);

            if (logger.logDebug()) {
                logger.debug(GT.tr("Closeable connections removed.  {0} Total physical connections remaining.", new Object[]{physicalConnections.size()}));
            }

            // Close them!
            for (int i = 0; i < closeable.size(); i++) {
                closeable.get(i).getConnection().close();
            }
            if (logger.logDebug()) {
                logger.debug(GT.tr("Successfully closed {0} closeable connections.", new Object[]{closeable.size()}));
            }

            physicalConnections.notify();
        }
    }
    
    private void allocatePhysicalConnection(final String user, final String password) throws SQLException {
        PhysicalXAConnection physicalXAConnection = new PhysicalXAConnection(
                (BaseConnection) super.getConnection(user, password), user);

        synchronized(physicalConnections) {
            physicalConnections.add(physicalXAConnection);
            physicalConnections.notify();
        }

        if (logger.logDebug()) {
            logger.debug(GT.tr("[{0}] - New physical connection allocated", new Object[]{physicalXAConnection.getBackendPID()}));
        }
    }
    
    PhysicalXAConnection resolvePhysicalConnection(final Xid xid) {
        synchronized(physicalConnections) {
            PhysicalXAConnection physical = null;
            
            // Look for the physical connection with this xid.
            for (int i = 0; i < physicalConnections.size(); i++) {
                // Lock the physical connection to test it's state.
                physical = physicalConnections.get(i);
                physical.getManagementLock().lock();
                try {
                    if (xid.equals(physical.getAssociatedXid())) {
                        return physical;
                    }
                } finally {
                    physical.getManagementLock().unlock();
                }
            }
        }
        return null;
    }
    
    
    /**
     * Resolves a backend physical connection to use for servicing the current invocation on the given logical connection.
     * 
     * @param logicalConnection The logical connection requesting a physical connection.
     * @param xid If null, any available connection not currently working on a xid or in a local TX may be returned.
     * 
     * @return 
     */
    PhysicalXAConnection resolvePhysicalConnection(final PGXAConnection logicalConnection, final Xid xid) throws PGXAException {
        PhysicalXAConnection physical = logicalConnection.getPhysicalXAConnection();
        
        // If the physical is servicing a xid, and it's not a match for the xid requested, we need a new physical.
        if (physical != null && physical.getAssociatedXid() != null && !physical.getAssociatedXid().equals(xid)) {
            synchronized(physicalConnections) {
                physical.disassociate(logicalConnection); // leave the xid alone...
                physicalConnections.notify();
            }
            physical = null;
        } else if (physical != null && xid != null && physical.getAssociatedXid() == null) {
            // If the physical is not servicing a xid, and we want to service a xid then we need to decouple from the current
            // physical, and look for one matching the xid.
            synchronized(physicalConnections) {
                physical.disassociate(logicalConnection); // leave the xid alone...
                physicalConnections.notify();
            }
            physical = null;
        }
        
        for (int attempts = 0; physical == null; attempts++) {
            synchronized(physicalConnections) {
                // Look for the physical connection with this xid.
                boolean found = false;
                for (int i = 0; i < physicalConnections.size() && !found; i++) {
                    // Lock the physical connection to test it's state.
                    physical = physicalConnections.get(i);
                    physical.getManagementLock().lock();
                    try {
                        if (physical.getAssociatedXid() != null && physical.getAssociatedXid().equals(xid)) {
                            physical.associate(logicalConnection, xid);
                            found = true;
                        }
                    } finally {
                        physical.getManagementLock().unlock();
                    }
                }

                // If there isn't one, loop again, and attempt to associate.
                for (int i = 0; i < physicalConnections.size() && !found; i++) {
                    physical = physicalConnections.get(i);
                    found = physical.associate(logicalConnection, xid);
                }

                // If that blows up, then we need to wait for a connection if we're configured to do so.
                if (!found) {
                    physical = null;

                    if (xaAcquireTimeout <= 0) {
                        throw new PGXAException(GT.tr("Support for Transaction Interleaving has been disabled. " + 
                                                "Set 'xaAcquireTimeout' > 0 to enable interleaving support"), XAException.XAER_RMERR);
                    } else if (attempts * xaWaitStep < xaAcquireTimeout) {
                        try {
                            physicalConnections.wait(20);
                        } catch (InterruptedException ie) {
                            throw new RuntimeException("Interrupted while attempting to resolve physical XA connection.", ie);
                        }
                    } else {
                        // Well, that didn't work. Time to open a new physical connection and let the next iteration pair.
                        if (logger.logDebug()) {
                            logger.debug(GT.tr("Attempting to open new physical connection."));
                        }

                        try {
                            allocatePhysicalConnection(logicalConnection.getUser(), logicalConnection.getPassword());
                        } catch (Exception ex) {
                            throw new PGXAException(GT.tr("Failed to open new physical connection."), ex, XAException.XAER_RMERR);
                        }
                    }
                }
            }
        }
        
        return physical;
    }
    
    /**
     * If the provided logical connection is associated to a physical connection servicing the provided xid,
     * then the physical connection has the current thread removed from it's associated set of threads. If 
     * there are no more threads using this association, then we allow the removal of the association 
     * between the logical and physical connection.
     * 
     * NOTE: This does -not- disassociate the xid from the physical connection,
     * that step is done after prepare / commit / rollback depending on the 
     * phase protocol (1pc, 2pc) in play.
     *
     * @param logicalConnection The logical connection to disassociate with the physical connection servicing the provided xid
     * @param xid The xid for the transaction
     * @throws XAException
     */
    void end(final PGXAConnection logicalConnection, final Xid xid) throws XAException {
        PhysicalXAConnection physicalConn = resolvePhysicalConnection(logicalConnection, xid);
        physicalConn.disassociate(logicalConnection); // We don't want to disassociate the xid, just the logical connection.

        if (logger.logDebug()) {
            logger.debug(GT.tr("{[0]} - Successfully disassociated xid: {1}", new Object[]{physicalConn.getBackendPID(), RecoveredXid.xidToString(xid)}));
        }
    }
    

    /**
     * Blocks until a physical connection is available to be pegged to an Xid.
     * 
     * Throws XAException if there is already a physical connection for the given Xid.
     * 
     * @param logicalConnection The logical connection start a transaction for the given Xid
     * @param xid The Xid for which to start the transaction
     * 
     * @throws XAException if the xid is already being serviced by a physical connection
     */
    void start(final PGXAConnection logicalConnection, final Xid xid) throws XAException {
        PhysicalXAConnection currentConn = resolvePhysicalConnection(xid);
        if (currentConn != null) {
            throw new XAException("Start invoked for an xid already serviced by a backend.");
        }

        // Obtain a physical connection to peg to the Xid.
        currentConn = resolvePhysicalConnection(logicalConnection, xid);

        if (logger.logDebug()) {
            logger.debug(GT.tr("[{0}] - Physical connection acquired to start transaction xid: {1}.", new Object[]{currentConn.getBackendPID(), RecoveredXid.xidToString(xid)}));
        }
    }

    /**
     * Locates the physical connection servicing the given Xid, pairs the given logicalConnectionId to that physical connection.
     * 
     * @param logicalConnection The logical connection to pair to the physical connection servicing the provided xid
     * @param xid The xid for the transaction to pair with the provided logical connection
     * @throws XAException if the given xid is not in a Suspended state.
     */
    void resume(final PGXAConnection logicalConnection, final Xid xid) throws XAException {

        if (logger.logDebug()) {
            logger.debug(GT.tr("Resuming transaction xid: {0}", new Object[]{RecoveredXid.xidToString(xid)}));
        }

        PhysicalXAConnection currentConn = resolvePhysicalConnection(logicalConnection, xid);
        if (!currentConn.isSuspended()) {
            throw new XAException("The backend connection servicing the resumed xid is not in a suspended state.");
        }

        if (logger.logDebug()) {
            logger.debug(GT.tr("[{0}] - Physical connection acquired to resume xid: {1}.", new Object[]{currentConn.getBackendPID(), RecoveredXid.xidToString(xid)}));
        }
    }

    /**
     * Locates the physical connection servicing the given xid, pairs the given logicalConnectionId to that physical connection, 
     * so long as it's not in a suspended state.
     * 
     * @param logicalConnection The logical connection to pair with the physical connection for the provided xid
     * @param xid The xid for the transaction to be associated with the logical connection
     * @throws XAException if the given xid backend is in a suspended state.
     */
    void join(final PGXAConnection logicalConnection, final Xid xid) throws XAException {

            if (logger.logDebug()) {
                logger.debug(GT.tr("Joining logical connection to transaction xid: {0}.", new Object[]{RecoveredXid.xidToString(xid)}));
            }

            PhysicalXAConnection currentConn = resolvePhysicalConnection(logicalConnection, xid);
            if (currentConn.isSuspended()) {
                throw new XAException("You cannot join a suspended xid. Instead, this should be a resume (JTA 1.0.1 spec) .");
            }

            if (logger.logDebug()) {
                logger.debug(GT.tr("[{0}] - Physical connection acquired to join xid: {1}.", new Object[]{currentConn.getBackendPID(), RecoveredXid.xidToString(xid)}));
            }
    }

    /**
     * Marks the transaction suspended for the provided xid if it is properly associated to the provided logical
     * connection.
     *
     * @param logicalConnection The logical connection associated to the xid to be suspended
     * @param xid The xid for the transaction to be suspended
     */
    void suspend(final PGXAConnection logicalConnection, final Xid xid) throws XAException {
        if (logger.logDebug()) {
            logger.debug(GT.tr("Suspending transaction xid: {0}.", new Object[]{RecoveredXid.xidToString(xid)}));
        }

        PhysicalXAConnection currentConn = resolvePhysicalConnection(logicalConnection, xid);
        currentConn.markSuspended();

        if (logger.logDebug()) {
            logger.debug(GT.tr("[{0}] - Transaction xid: {1} suspended.", new Object[]{currentConn.getBackendPID(), RecoveredXid.xidToString(xid)}));
        }
    }

    /**
     * Issues PREPARE TRANSACTION statement to the connection associated with the provided xid.  Then the connection's
     * original autoCommit setting is reinstated and the connection is disassociated with the provided xid.
     *
     * Future extension point - The specification enables a return of XA_RDONLY if all queries inside the transaction
     * where only reads.  This would require further tracking which isn't being done yet.  This could be a future
     * performance improvement.
     *
     * @param xid The xid for the transaction to prepare
     * @return XAResource.XA_OK if all went well.  This does not track queries in order to return XA_RDONLY yet.
     */
    int prepare(final PGXAConnection logicalConnection, final Xid xid) throws XAException {
        if (logger.logDebug()) {
            logger.debug(GT.tr("Preparing transaction xid: {0}.", new Object[]{RecoveredXid.xidToString(xid)}));
        }

        PhysicalXAConnection currentConn = resolvePhysicalConnection(xid);
        if (currentConn == null || !xid.equals(currentConn.getAssociatedXid())) {
            throw new PGXAException(GT.tr("No backend connection currently servicing xid to issue prepare to."), XAException.XAER_RMERR);
        }

        if (logger.logDebug()) {
            logger.debug(GT.tr("[{0}] - Physical connection acquired to prepare xid: {1}.", new Object[]{currentConn.getBackendPID(), RecoveredXid.xidToString(xid)}));
        }
        try {
            String s = RecoveredXid.xidToString(xid);

            Statement stmt = currentConn.getConnection().createStatement();
            try {
                stmt.executeUpdate("PREPARE TRANSACTION '" + s + "'");
            } finally {
                stmt.close();
            }

            if (logger.logDebug()) {
                logger.debug(GT.tr("[{0}] - Transaction xid: {1} prepared.", new Object[]{currentConn.getBackendPID(), s}));
            }

            synchronized(physicalConnections) {
                currentConn.disassociate(xid);
                physicalConnections.notify();
            }

            return XAResource.XA_OK;
        } catch (SQLException ex) {
            throw new PGXAException(GT.tr("Error preparing transaction"), ex, XAException.XAER_RMERR);
        }
    }

    void commit(final PGXAConnection logicalConnection, final Xid xid, final boolean onePhase) throws XAException {
        PhysicalXAConnection currentConn = resolvePhysicalConnection(logicalConnection, xid);
        if (onePhase) {
            if (currentConn == null) {
                throw new PGXAException(GT.tr("No backend was found servicing the given xid."), XAException.XAER_INVAL);
            }
            if (currentConn.isSuspended()) {
                throw new PGXAException(GT.tr("The backend servicing the given xid. Is marked suspended."), XAException.XAER_INVAL);
            }

            if (logger.logDebug()) {
                logger.debug(GT.tr("[{0}] - Physical connection acquired for one-phase commit xid: {1}.", new Object[]{currentConn.getBackendPID(), RecoveredXid.xidToString(xid)}));
            }

            try {
                currentConn.getConnection().commit();
                synchronized(physicalConnections) {
                    currentConn.disassociate(logicalConnection, xid);
                    physicalConnections.notify();
                }

                if (logger.logDebug()) {
                    logger.debug(GT.tr("[{0}] - One-phase commit complete for xid: {1}.", new Object[]{currentConn.getBackendPID(), RecoveredXid.xidToString(xid)}));
                }
            } catch (SQLException sqle) {
                throw new PGXAException(GT.tr("Error during one-phase commit"), sqle, XAException.XAER_RMERR);
            }
        } else {
            if (logger.logDebug()) {
                logger.debug(GT.tr("[{0}] - Physical connection acquired for two-phase commit xid: {1}.", new Object[]{currentConn.getBackendPID(), RecoveredXid.xidToString(xid)}));
            }

            try {
                String s = RecoveredXid.xidToString(xid);

                boolean restoreAutoCommit = currentConn.getConnection().getAutoCommit();
                currentConn.getConnection().setAutoCommit(true);
                Statement stmt = currentConn.getConnection().createStatement();

                try {
                    if (logger.logDebug()) {
                        logger.debug(GT.tr("[{0}] - Commit prepared transaction xid: {1}.", new Object[]{currentConn.getBackendPID(), s}));
                    }
                    stmt.executeUpdate("COMMIT PREPARED '" + s + "'");
                } finally {
                    stmt.close();
                    if (logger.logDebug()) {
                        logger.debug(GT.tr("[{0}] - Prepared transaction xid: {1} committed.", new Object[]{currentConn.getBackendPID(), s}));
                    }
                }

                currentConn.getConnection().setAutoCommit(restoreAutoCommit);
                synchronized(physicalConnections) {
                    currentConn.disassociate(logicalConnection, xid);
                    physicalConnections.notify();
                }

                if (logger.logDebug()) {
                    logger.debug(GT.tr("[{0}] - Auto-commit restored to {1}.", new Object[]{currentConn.getBackendPID(), restoreAutoCommit}));
                }
            } catch (SQLException sqle) {
                throw new PGXAException(GT.tr("Error committing prepared transaction"), sqle, XAException.XAER_RMERR);
            }
        }
    }

    /**
     * If there is a physical connection for the xid, then we rollback right on that connection.
     * 
     * If there is no physical connection for the xid, we assume we've been told to rollback a prepared xid.
     * 
     * @param xid The xid for the transaction to rollback
     * @throws XAException 
     */
    void rollback(final PGXAConnection logicalConnection, final Xid xid) throws XAException {
        if (logger.logDebug()) {
            logger.debug(GT.tr("Rolling back transaction xid: {1}.", new Object[]{RecoveredXid.xidToString(xid)}));
        }

        PhysicalXAConnection currentConn = resolvePhysicalConnection(xid);
        if (currentConn != null) { // one phase!
            try {
                currentConn.getConnection().rollback();
            } catch (SQLException sqle) {
                throw new PGXAException(GT.tr("Error during one-phase rollback"), sqle, XAException.XAER_RMERR);
            }
        } else {
            currentConn = resolvePhysicalConnection(logicalConnection, xid);

            // Assume a two phase, if it fails, well, then shoot.
            try {
                String s = RecoveredXid.xidToString(xid);

                currentConn.getConnection().setAutoCommit(true);
                Statement stmt = currentConn.getConnection().createStatement();
                try {
                    stmt.executeUpdate("ROLLBACK PREPARED '" + s + "'");
                } finally {
                    stmt.close();
                }
            } catch (SQLException sqle) {
                throw new PGXAException(GT.tr("Error during two-phase rollback"), sqle, XAException.XAER_RMERR);
            }
        }
        
        // Release everything.
        synchronized(physicalConnections) {
            currentConn.disassociate();
            physicalConnections.notify();
        }
        
        if (logger.logDebug()) {
            logger.debug(GT.tr("[{0}] - Transaction xid: {1} rolled back.", new Object[]{currentConn.getBackendPID(), RecoveredXid.xidToString(xid)}));
        }
    }

    /**
     * If there's a physical backend handling the given xid, it's underlying connection is rolled back, the state is reset.
     *
     * @param xid the transaction to rollback and forget
     * @throws XAException 
     */
    void forget(final Xid xid) throws XAException {
        if (logger.logDebug()) {
            logger.debug(GT.tr("Forgetting transaction xid: {1}.", new Object[]{RecoveredXid.xidToString(xid)}));
        }

        PhysicalXAConnection fugeddaboutit = resolvePhysicalConnection(xid);
        if (fugeddaboutit != null) {
            boolean rolledback = false;
            try {
                fugeddaboutit.getConnection().rollback();
                rolledback = true;
            } catch (SQLException sqle) {
                throw new PGXAException(GT.tr("Error rolling back physical connection servicing the given xid."), XAException.XAER_RMERR);
            } finally {
                synchronized(physicalConnections) {
                    fugeddaboutit.disassociate();
                    physicalConnections.notify();
                }

                ArrayList<PGXAConnection> forgetAssociations = new ArrayList<PGXAConnection>();

                // If we were unable to rollback, we need to kill any logical connections pegged to this thing, and the physical connections.
                if (!rolledback) {
                    for (PGXAConnection logical : forgetAssociations) {
                        try {
                            logical.close();
                        } catch (SQLException sqle) {
                            logger.debug(GT.tr("Failed closing logical connection!"), sqle);
                        }
                    }

                    physicalConnections.remove(fugeddaboutit);
                    try {
                        fugeddaboutit.getConnection().close();
                    } catch (SQLException sqle) {
                        logger.debug(GT.tr("Failed closing physical connection!"), sqle);
                    }
                }
            }
        } // nothing to forget about.
    }

    @Override
    public Reference getReference() throws NamingException {
        Reference ref = super.getReference();
        ref.add(new StringRefAddr("xaWaitStep", Integer.toString(xaWaitStep)));
        ref.add(new StringRefAddr("xaAcquireTimeout", Integer.toString(xaAcquireTimeout)));
        
        return ref;
    }

    @Override
    protected void writeBaseObject(ObjectOutputStream out) throws IOException {
        super.writeBaseObject(out);
        out.writeInt(xaWaitStep);
        out.writeInt(xaAcquireTimeout);
    }

    @Override
    protected void readBaseObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        super.readBaseObject(in);
        xaWaitStep = in.readInt();
        xaAcquireTimeout = in.readInt();
    }

    public void setXAWaitStep(int xaWaitStep) {
        this.xaWaitStep = xaWaitStep;
    }

    public int getXAWaitStep() {
        return xaWaitStep;
    }

    public void setXAAcquireTimeout(int xaAcquireTimeout) {
        this.xaAcquireTimeout = xaAcquireTimeout;
    }

    public int getXAAcquireTimeout() {
        return xaAcquireTimeout;
    }

    /**
     * Handles invoking Connection and PGConnection upon a physical backend, 
     * based upon the current state of things managed by the XAResource i
     */
    private class LogicalXAConnectionHandler implements InvocationHandler {
        private PGXAConnection logicalConnection;

        void setLogicalConnection(final PGXAConnection logicalConnection) {
            this.logicalConnection = logicalConnection;
        }

        /**
         * Intercepts method calls and invokes them on the proxied object instead.  If there is a transaction being serviced
         * and that transaction is between a start and disassociate/suspend, commit, rollback, setSavePoint, and setAutoCommit are
         * disallowed.
         *
         * @param proxy The proxy instance on which the method was invoked
         * @param method The method being invoked by proxy
         * @param args The arguments to the method
         * @return The return value from the invoked method
         * @throws Throwable - a PSQLException is thrown if a disallowed method is called while the physical connection is
         * in an unexpected state.
         */
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            // Get and peg a physical connection to service this logicalConnection.
            // If there is already an association in place, we want to use that
            // physical connections.
            PhysicalXAConnection physicalConnection = logicalConnection.getPhysicalXAConnection();
            if (physicalConnection == null) {
                physicalConnection = resolvePhysicalConnection(logicalConnection, null);
            }
                        
            // If the physical connection is servicing an Xid (between start / end)
            String methodName = method.getName();
            if (physicalConnection.getAssociatedXid() != null) {
                if (methodName.equals("commit") ||
                    methodName.equals("rollback") ||
                    methodName.equals("setSavePoint") ||
                    (methodName.equals("setAutoCommit") && ((Boolean) args[0])))
                {
                    throw new PSQLException(GT.tr("Transaction control methods setAutoCommit(true), commit, rollback and setSavePoint not allowed while an XA transaction is active."),
                            PSQLState.OBJECT_NOT_IN_STATE);
                }
            }
            
            try {
                return method.invoke(physicalConnection.getConnection(), args);
            } catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        }
    }
}
