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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.StringRefAddr;
import javax.sql.XAConnection;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

/**
 * An XADataSource implementation which complies with the JTA spec for interleaving, resource sharing, etc.
 * 
 * @author bvarner
 */
public class PGXADataSource extends AbstractPGXADataSource {
    /**
     * Collection of physicalConnections to the backend data source.
     * 
     * Invocations on the Logical connection are routed to an available 
     * backend connection via a Proxy InvocationHandler.
     */
    final List<PhysicalXAConnection> physicalConnections = 
            Collections.synchronizedList(new ArrayList<PhysicalXAConnection>());

    /**
     * Maintains association of logical connection ids servicing Connection 
     * invocations to the proper physical backend.
     */
    final Map<PGXAConnection, PhysicalXAConnection> logicalMappings = 
            Collections.synchronizedMap(new HashMap<PGXAConnection, PhysicalXAConnection>());

    /**
     * Time to wait for a physical connection to become available before we create a new physical connection to service a logical.
     */
    private int xaAcquireTimeout = 500; // in milliseconds
    
    /**
     * Time to wait for a physical connection to become available before checking again.
     * Once we've waited up to acquirePhysicalTimeout, we'll create a new backend.
     */
    private int xaWaitStep = 25; // in milliseconds;
    
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
        // Allocate and add a new physical connection to service the front-end
        // XAConnections we return.
        physicalConnections.add(new PhysicalXAConnection((BaseConnection)super.getConnection(user, password), user, password));
        
        // Create a new LogicalXAConnectionHandler proxy.
        LogicalXAConnectionHandler logicalHandler = new LogicalXAConnectionHandler();
        
        PGXAConnection logicalConnection = new PGXAConnection(user, (Connection)Proxy.newProxyInstance(getClass().getClassLoader(), 
                        new Class[]{Connection.class, PGConnection.class}, 
                        logicalHandler), this);
        
        logicalHandler.setLogicalConnection(logicalConnection);
        
        return logicalConnection;
    }

    /**
     * Pairs the provided logical and physical connections.
     *
     * @param logicalConnection The logical connection to be associated to the provided physical connection
     * @param backend The physical connection to be associated to the provided logical connection
     */
    private void associate(final PGXAConnection logicalConnection, final PhysicalXAConnection backend) {
        synchronized (logicalMappings) {
            logicalMappings.put(logicalConnection, backend);
            logicalMappings.notify();
        }
    }

    /**
     * If the provided logical connection is associated to a physical connection servicing the provided xid, the
     * mapping for the logical connection and physical connection is removed.  The mappings collection is then notified
     * for any resources blocking while waiting for an available connection.
     *
     * @param logicalConnection The logical connection to disassociate with the physical connection servicing the provided xid
     * @param xid The xid for the transaction
     * @throws XAException
     */
    void disassociate(final PGXAConnection logicalConnection, final Xid xid) throws XAException {
        synchronized (logicalMappings) {
            verifyAssociation(logicalConnection, xid);
            logicalMappings.remove(logicalConnection);
            logicalMappings.notify();
        }
    }

    /**
     * Verifies that the given logicalConnectionId is already associated to a physical connection, and returns the physical connection.
     * 
     * @param logicalConnection The logical connection that should exist in the connection mapping
     * @param xid The xid the logical connection should be associated with
     * @return The PhysicalXAConnection if one is properly associated, otherwise, throws XAException
     */
    private PhysicalXAConnection verifyAssociation(final PGXAConnection logicalConnection, final Xid xid) throws XAException {
        PhysicalXAConnection currentConn = logicalMappings.get(logicalConnection);
        if (currentConn == null || !currentConn.getAssociatedXid().equals(xid)) {
            throw new PGXAException(GT.tr("logical XAConnection not associated with given xid."), XAException.XAER_INVAL);
        }
        return currentConn;
    }
    
    /**
     * Blocks until a physical connection is free, and associates it to the given logical connection (if one is supplied)
     *
     * @param logicalConnection the logical connection to associate with the physical connection
     * @return An available PhysicalXAConnection (associated to a logical connection if provided)
     */
    PhysicalXAConnection getPhysicalConnection(final PGXAConnection logicalConnection, final boolean requireInactive) throws PGXAException {
        PhysicalXAConnection available = logicalMappings.get(logicalConnection);
        
        if (requireInactive && (available != null && available.getAssociatedXid() != null)) {
            available = null;
        }
        
        try {
            for (int attempts = 0; available == null; attempts++) {
                // We're going to try to map this logical connection to a new
                // physical backend. Lock the mapping to serialize access.
                synchronized (logicalMappings) {
                    synchronized (physicalConnections) {
                        for (int i = 0; i < physicalConnections.size(); i++) {
                            available = physicalConnections.get(i);
                            if (available.getAssociatedXid() == null &&     // If no xid in service, even suspended has an xid.
                                !logicalMappings.containsValue(available) &&  // Not associated to a logical
                                available.getUser().equals(logicalConnection.getUser())) // Has the same user if applicable
                            {
                                // Means that we're 'idle' and 'available' for either XA or non-XA work.
                                break;
                            } else {
                                available = null;
                            }
                        }
                    }

                    // We found an available physical connection!
                    if (logicalConnection != null && available != null) {
                        associate(logicalConnection, available);
                    } else if (available == null) {
                        // Oh Noes! We didn't have an xid pegged physical connection for
                        // this logical connection, and there weren't any physical 
                        // connections available to assign to this logical connection!
                        // 
                        // A few things could have happened here.
                        // 1.) We have all our backends pegged to an xid, and this is
                        //     is an attempt to use a non-2pc XAConnection outside of a 
                        //     start / end block (allowable! by the JTA spec!)
                        // 2.) We have all our transactions associated and we've been
                        //     asked to do something to another xid which we don't have
                        //     an active backend for.
                        // 
                        // If you have a pool of size 1, and the backend is tied up in 
                        // a 2pc start / end block, invoking getConnection() on the 
                        // XAConnection returned from the DS in order to issue non-tx 
                        // commands is invalid, according to the jdbc2 spec on pooling
                        // connections and the getConnection() / close() cycle.
                        // In the event that there is a 2pc in progress, the TM should
                        // at some point issue an end(), followed by the rest of the 
                        // appropriate methods to kick the connection back into an 
                        // idle state.
                        // 
                        // We have a few options here (from an implementation perspective)
                        // which we can try.
                        // 1.) Allocate a new physical connection with the same user & 
                        //     password as the logical connection. If we cannot allocate
                        //     a connection, then we need to throw an XAException and 
                        //     give up.
                        //     (multiple physical connections for a logical connection)
                        // 
                        // 2.) Block until a backend physical connection becomes available.
                        //     Eventually the TM will end() / prepare / commit / rollback
                        //     one of the xids servicing, unless we're in a single-connection
                        //     single thread of execution deadlock -- where the only option
                        //     which will clear the lock is a successful implementation of 
                        //     option #1.
                        //
                        // 
                        // So, let's start by attempting option #2 -- blocking for a period of time.
                        if (attempts * xaWaitStep < xaAcquireTimeout) {
                            logicalMappings.wait(xaWaitStep);
                        } else { // Well, that didn't work. Time to open a new physical connection and let the next iteration pair.
                            String user = logicalConnection.getUser();
                            try {
                                String password = findPasswordFor(user);
                                physicalConnections.add(new PhysicalXAConnection((BaseConnection)super.getConnection(user, password), user, password));
                            } catch (Exception ex) {
                                throw new PGXAException(GT.tr(""), ex, XAException.XAER_RMFAIL);
                            }
                        }
                    }
                }
            }
        } catch (InterruptedException ie) {
            // TODO: Log this.
            available = null;
        }

        return available;
    }

    /**
     * Look through the existing collection of physicalConnections, and extract a password credential for the first matching user.
     * 
     * @param user
     * 
     * @return The password used to create a physical connection for the given user.
     * @throws IllegalStateException if no physical connection exists for the given user.
     */
    private String findPasswordFor(final String user) {
        synchronized (physicalConnections) {
            PhysicalXAConnection candidate = null;
            for (int i = 0; i < physicalConnections.size(); i++) {
                candidate = physicalConnections.get(i);
                if (candidate != null && user.equals(candidate.getUser())) {
                    return candidate.getPassword();
                }
            }
        }
        throw new IllegalStateException(GT.tr("No existing physical connection for user '{0}'", user));
    }
    
    /**
     * Non-Blocking method returning an already associated physical connection for the given Xid, or null if no association exists.
     * 
     * @param xid The xid used to find an associated PhysicalXAConnection
     * 
     * @return A PhysicalXAConnection for the given xid, if one exists. Otherwise, null.
     */
    PhysicalXAConnection getPhysicalConnection(final Xid xid) {
        PhysicalXAConnection peggedToXid = null;
        synchronized (physicalConnections) {
            for (int i = 0; i < physicalConnections.size() && peggedToXid == null; i++) {
                peggedToXid = physicalConnections.get(i);
                // This looks strange, because the XADataSourceTest isn't checking for null in the equals() of it's fake Xid class.
                if (peggedToXid.getAssociatedXid() == null || !xid.equals(peggedToXid.getAssociatedXid())) {
                    // So if the candidate has no xid, or is not equal, this is not the xid you're looking for.
                    peggedToXid = null;
                }
            }
        }
        return peggedToXid;
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
    synchronized void start(final PGXAConnection logicalConnection, final Xid xid) throws XAException {
        PhysicalXAConnection currentConn = getPhysicalConnection(xid);
        if (currentConn != null) {
            throw new XAException("Start invoked for an xid already serviced by a backend.");
        }
        // Obtain a physical connection to peg to the Xid.
        currentConn = getPhysicalConnection(logicalConnection, true);
        
        currentConn.setAssociatedXid(xid);
        
        associate(logicalConnection, currentConn);
    }

    /**
     * Locates the physical connection servicing the given Xid, pairs the given logicalConnectionId to that physical connection.
     * 
     * @param logicalConnection The logical connection to pair to the physical connection servicing the provided xid
     * @param xid The xid for the transaction to pair with the provided logical connection
     * @throws XAException if the given xid is not in a Suspended state.
     */
    void resume(final PGXAConnection logicalConnection, final Xid xid) throws XAException {
        PhysicalXAConnection currentConn = getPhysicalConnection(xid);
        if (!currentConn.isSuspended()) {
            throw new XAException("The backend connection servicing the resumed xid is not in a suspended state.");
        }
        associate(logicalConnection, currentConn);
        currentConn.setAssociatedXid(xid); // Marks the connection unsuspended
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
        PhysicalXAConnection currentConn = getPhysicalConnection(xid);
        if (currentConn.isSuspended()) {
            throw new XAException("You cannot join a suspended xid. Instead, this should be a join (JTA 1.0.1 spec .");
        }
        associate(logicalConnection, currentConn);
    }

    /**
     * Marks the transaction suspended for the provided xid if it is properly associated to the provided logical
     * connection.
     *
     * @param logicalConnection The logical connection associated to the xid to be suspended
     * @param xid The xid for the transaction to be suspended
     */
    void suspend(final PGXAConnection logicalConnection, final Xid xid) throws XAException {
        PhysicalXAConnection currentConn = verifyAssociation(logicalConnection, xid);
        currentConn.markSuspended();
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
    int prepare(final Xid xid) throws XAException {
        PhysicalXAConnection currentConn = getPhysicalConnection(xid);
        if (currentConn == null) {
            throw new XAException("No backend connection currently servicing xid to issue prepare to.");
        }

        try {
            String s = RecoveredXid.xidToString(xid);

            // Do NOT try to set autocommit on a PREPARE TRANSACTION.
            Statement stmt = currentConn.getConnection().createStatement();
            try {
                stmt.executeUpdate("PREPARE TRANSACTION '" + s + "'");
            } finally {
                stmt.close();
            }

            currentConn.setAssociatedXid(null);
            return XAResource.XA_OK;
        } catch (SQLException ex) {
            throw new PGXAException(GT.tr("Error preparing transaction"), ex, XAException.XAER_RMERR);
        }
    }

    /**
     * Executes a one-phase commit on a backend.
     * 
     * @param xid The xid for the transaction on which to issue the commit
     * @throws XAException 
     */
    void commitOnePhase(final Xid xid) throws XAException {
        PhysicalXAConnection currentConn = getPhysicalConnection(xid);
        if (currentConn == null) {
            throw new PGXAException(GT.tr("No backend was found servicing the given xid."), XAException.XAER_INVAL);
        }
        if (currentConn.isSuspended()) {
            throw new PGXAException(GT.tr("The backend servicing the given xid. Is marked suspended."), XAException.XAER_INVAL);
        }

        try {
            currentConn.getConnection().commit();
            currentConn.setAssociatedXid(null);
        } catch (SQLException sqle) {
            throw new PGXAException(GT.tr("Error during one-phase commit"), sqle, XAException.XAER_RMERR);
        }
    }

    /**
     * Executes a two-phase commit on a backend for the given xid.
     * 
     * @param xid The xid for the transaction on which to issue the commit
     */
    void commitPrepared(final PGXAConnection logicalConnection, final Xid xid) throws XAException {
        PhysicalXAConnection currentConn = getPhysicalConnection(xid);
        if (currentConn == null) {
            currentConn = getPhysicalConnection(logicalConnection, true); // We don't care which one, so long as we can get one.
        }
        
        try {
            String s = RecoveredXid.xidToString(xid);

            boolean autoCommitRestore = currentConn.getConnection().getAutoCommit();
            currentConn.getConnection().setAutoCommit(true);
            Statement stmt = currentConn.getConnection().createStatement();
            try {
                stmt.executeUpdate("COMMIT PREPARED '" + s + "'");
            } finally {
                stmt.close();
            }
            currentConn.getConnection().setAutoCommit(autoCommitRestore);
        } catch (SQLException sqle) {
            throw new PGXAException(GT.tr("Error committing prepared transaction"), sqle, XAException.XAER_RMERR);
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
        PhysicalXAConnection currentConn = getPhysicalConnection(xid);
        if (currentConn != null) { // one phase!
            try {
                currentConn.getConnection().rollback();
                currentConn.setAssociatedXid(null);
            } catch (SQLException sqle) {
                throw new PGXAException(GT.tr("Error during one-phase rollback"), sqle, XAException.XAER_RMERR);
            }
        } else {
            currentConn = getPhysicalConnection(logicalConnection, true);
            
            // Assume a two phase, if it fails, well, then shoot.
            try {
                String s = RecoveredXid.xidToString(xid);

                boolean restoreAutoCommit = currentConn.getConnection().getAutoCommit();
                currentConn.getConnection().setAutoCommit(true);
                Statement stmt = currentConn.getConnection().createStatement();
                try {
                    stmt.executeUpdate("ROLLBACK PREPARED '" + s + "'");
                } finally {
                    stmt.close();
                }
                
                // Restore autocommit.
                currentConn.getConnection().setAutoCommit(restoreAutoCommit);
            } catch (SQLException sqle) {
                throw new PGXAException(GT.tr("Error during two-phase rollback"), sqle, XAException.XAER_RMERR);
            }
        }
    }

    /**
     * If there's a physical backend handling the given xid, it's underlying connection is rolled back, the state is reset
     * @param xid the transaction to rollback and forget
     * @throws XAException 
     */
    void forget(final Xid xid) throws XAException {
        PhysicalXAConnection fugeddaboutit = getPhysicalConnection(xid);
        if (fugeddaboutit != null) {
            boolean rolledback = false;
            try {
                fugeddaboutit.getConnection().rollback();
                rolledback = true;
            } catch (SQLException sqle) {
                throw new PGXAException(GT.tr("Error rolling back physical connection servicing the given xid."), XAException.XAER_RMERR);
            } finally {
                fugeddaboutit.setAssociatedXid(null);
                
                ArrayList<PGXAConnection> forgetAssociations = new ArrayList<PGXAConnection>();
                // Remove / Update any logical mappings.
                if (logicalMappings.containsValue(fugeddaboutit)) {
                    synchronized(logicalMappings) {
                        ArrayList<PGXAConnection> logicalConnections = new ArrayList<PGXAConnection>();
                        for (Map.Entry<PGXAConnection, PhysicalXAConnection> pairing : logicalMappings.entrySet()) {
                            if (pairing.getValue().equals(fugeddaboutit)) {
                                logicalConnections.add(pairing.getKey());
                            }
                        }
                        for (int i = 0; i < logicalConnections.size(); i++) {
                            logicalMappings.remove(logicalConnections.get(i));
                        }
                        logicalMappings.notify();
                    }
                }
                
                // If we were unable to rollback, we need to kill any logical connections pegged to this thing, and the physical connections.
                if (!rolledback) {
                    for (PGXAConnection logical : forgetAssociations) {
                        try {
                            logical.close();
                        } catch (SQLException sqle) { 
                            // TODO: Log that this failed, although we expect it to, really.
                        }
                    }
                    
                    physicalConnections.remove(fugeddaboutit);
                    try {
                        fugeddaboutit.getConnection().close();
                    } catch (SQLException sqle) { 
                        // TODO: Log that this failed to clean up nicely.
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
         * and that transaction is between a start and end/suspend, commit, rollback, setSavePoint, and setAutoCommit are
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
            // Get and peg a physical connection to a backend, if we're in a 
            // start / end, then we'll have an association in place, otherwise
            // we'll get a non-transactional (one-phase) resource back.
            PhysicalXAConnection physicalConnection = getPhysicalConnection(logicalConnection, false);
                        
            // If the physical connection is servicing an Xid (between start / end, or suspended)
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
            
            // If a logical connection is closing, we have at least 1 physical connection pegged to it.
            // We may have more than one physical connection which is 'idle', created to service interleaving / resource sharing.
            if (methodName.equals("close")) {
                List<PhysicalXAConnection> closeable = new ArrayList<PhysicalXAConnection>();

                synchronized (physicalConnections) {
                    PhysicalXAConnection candidate = null;
                    for (int i = 0; i < physicalConnections.size(); i++) {
                        candidate = physicalConnections.get(i);
                        if (candidate.getAssociatedXid() == null &&     // If no xid in service, even suspended has an xid.
                            !logicalMappings.containsValue(candidate) &&  // Not associated to a logical
                            candidate.getUser().equals(logicalConnection.getUser())) // Has the same user.
                        {
                            closeable.add(candidate);
                        }
                    }

                    physicalConnections.removeAll(closeable);
                }

                // Close them!
                for (int i = 0; i < closeable.size(); i++) {
                    closeable.get(i).getConnection().close();
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
