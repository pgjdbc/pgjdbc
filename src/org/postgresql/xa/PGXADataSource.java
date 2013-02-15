/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2013, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package org.postgresql.xa;

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
     * Collection of front-end (logical) XAConnections to clients (TM, applications).
     */
    final List<PGXAConnection> logicalConnections = 
            Collections.synchronizedList(new ArrayList<PGXAConnection>());
    
    /**
     * Maintains association of logical connection ids servicing Connection 
     * invocations to the proper physical backend.
     */
    final Map<PGXAConnection, PhysicalXAConnection> logicalMappings = 
            Collections.synchronizedMap(new HashMap<PGXAConnection, PhysicalXAConnection>());
    
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
    public XAConnection getXAConnection(String user, String password) throws SQLException
    {
        // Allocate and add a new physical connection to service the front-end
        // XAConnections we return.
        physicalConnections.add(new PhysicalXAConnection((BaseConnection)super.getConnection(user, password)));
        
        // Create a new LogicalXAConnectionHandler proxy.
        LogicalXAConnectionHandler logicalHandler = new LogicalXAConnectionHandler();

        PGXAConnection logicalConnection = new PGXAConnection(user, (Connection)Proxy.newProxyInstance(getClass().getClassLoader(), 
                        new Class[]{Connection.class, PGConnection.class}, 
                        logicalHandler), this);
        
        logicalHandler.setLogicalConnection(logicalConnection);
        
        return logicalConnection;
    }
    
    
    
    
    private void associate(final PGXAConnection logicalConnection, final PhysicalXAConnection backend) {
        synchronized (logicalMappings) {
            logicalMappings.put(logicalConnection, backend);
            logicalMappings.notify();
        }
    }
    
    
    
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
     * @param logicalConnection
     * @param xid 
     * @return The PhysicalXAConnection if one is properly associated, otherwise, throws XAException
     */
    private PhysicalXAConnection verifyAssociation(final PGXAConnection logicalConnection, final Xid xid) throws XAException {
        PhysicalXAConnection currentConn = logicalMappings.get(logicalConnection);
        if (currentConn == null || !currentConn.getAssociatedXid().equals(xid)) {
            throw new PGXAException(GT.tr("logical XAConnection not associated with given xid."), XAException.XAER_INVAL);
        }
        return currentConn;
    }

    PhysicalXAConnection getPhysicalConnection() {
        return getPhysicalConnection((PGXAConnection)null);
    }
    
    /**
     * Blocks until a physical connection is free, and associates it to the given logical connection (if one is supplied)
     * 
     * @return 
     */
    PhysicalXAConnection getPhysicalConnection(final PGXAConnection logicalConnection) {
        PhysicalXAConnection available = logicalConnection != null ? logicalMappings.get(logicalConnection) : null;
        try {
            for (int attempts = 0; available == null; attempts++) {
                // We're going to try to map this logical connection to a new
                // physical backend. Lock the mapping to serialize access.
                synchronized (logicalMappings) {
                    synchronized (physicalConnections) {
                        for (int i = 0; i < physicalConnections.size(); i++) {
                            available = physicalConnections.get(i);
                            if (available.getAssociatedXid() == null &&     // If no xid in service, even suspended has an xid.
                                !logicalMappings.containsValue(available))  // Not associated to a logical
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
                        //     and active backend for.
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
                        // If there is a backend associated to a logical connection
                        // with no xid attached, and with autocommit enabled, we
                        // can break the association with the existing logical 
                        // connection, and return that physical backend here.
                        PGXAConnection safeToSever = null;
                        for (Map.Entry<PGXAConnection, PhysicalXAConnection> entry : logicalMappings.entrySet()) {
                            try {
                                if (entry.getValue().getAssociatedXid() == null && 
                                    entry.getValue().getConnection().getAutoCommit()) 
                                {
                                    safeToSever = entry.getKey();
                                    break;
                                }
                            } catch (SQLException sqle) {
                                safeToSever = null;
                            }
                        }
                        if (safeToSever != null) {
                            available = logicalMappings.remove(safeToSever);
                            logicalMappings.notify();
                        } else {
                            // If we do not have one of those, there are a few 
                            // other options available, but yet to be implemented.
                            // 1.) Allocate a new physical connection, peg it to the logical
                            //     connection in another structure. 
                            //     (pool of physical connections for a logical connection)
                            // 2.) Block until a backend physical connection becomes available.
                            //     Eventually the TM will end() / prepare / commit / rollback
                            //     one of the xids servicing things, right?
                            //
                            // It will be slightly more complext to implement #2, but would
                            // be more 'correct' wrt connection pooling, and will not impose
                            // 'fuzzy math' on poor folks trying to size things appropriately.

                            // So, let's start by attempting option #2 -- blocking for a period of time.
                            logicalMappings.wait(500);
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
     * Non-Blocking method returning an already associated physical connection for the given Xid, or null if no association exists.
     * 
     * @param xid
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
                    // So if the canidate has no xid, or is not equal, this is not the xid you're looking for.
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
     * @param logicalConnection
     * @param xid
     * @param flags
     * 
     * @throws XAException if the xid is already being serviced by a physical connection
     */
    synchronized void start(final PGXAConnection logicalConnection, final Xid xid) throws XAException {
        PhysicalXAConnection currentConn = getPhysicalConnection(xid);
        if (currentConn != null) {
            throw new XAException("Start invoked for an xid already serviced by a backend.");
        }
        // Obtain a physical connection to peg to the Xid.
        currentConn = getPhysicalConnection(logicalConnection);
        
        currentConn.setAssociatedXid(xid);
        
        associate(logicalConnection, currentConn);
    }
    
    /**
     * Locates the physical connection servicing the given Xid, pairs the given logicalConnectionId to that physical connection.
     * 
     * @param logicalConnection
     * @param xid 
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
     * @param logicalConnection
     * @param xid
     * @throws XAException if the given xid backedn is in a suspended state.
     */
    void join(final PGXAConnection logicalConnection, final Xid xid) throws XAException {
        PhysicalXAConnection currentConn = getPhysicalConnection(xid);
        if (currentConn.isSuspended()) {
            throw new XAException("You cannot join a suspended xid. Instead, this should be a join (JTA 1.0.1 spec .");
        }
        associate(logicalConnection, currentConn);
    }
    
    /**
     * Marks the 
     */
    void suspend(final PGXAConnection logicalConnection, final Xid xid) throws XAException {
        PhysicalXAConnection currentConn = verifyAssociation(logicalConnection, xid);
        currentConn.markSuspended();
    }
    
    /**
     * 
     * @param xid
     * @return 
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
     * @param xid
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
     * @param xid
     */
    void commitPrepared(final Xid xid) throws XAException {
        PhysicalXAConnection currentConn = getPhysicalConnection(xid);
        if (currentConn == null) {
            currentConn = getPhysicalConnection(); // We don't care which one, so long as we can get one.
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
     * @param xid
     * @throws XAException 
     */
    void rollback(final Xid xid) throws XAException {
        PhysicalXAConnection currentConn = getPhysicalConnection(xid);
        if (currentConn != null) { // one phase!
            try {
                currentConn.getConnection().rollback();
                currentConn.setAssociatedXid(null);
            } catch (SQLException sqle) {
                throw new PGXAException(GT.tr("Error during one-phase rollback"), sqle, XAException.XAER_RMERR);
            }
        } else {
            currentConn = getPhysicalConnection();
            
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
     * @param xid
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
    
    
    /**
     * Handles invoking Connection and PGConnection upon a physical backend, 
     * based upon the current state of things managed by the XAResource i
     */
    private class LogicalXAConnectionHandler implements InvocationHandler {
        private PGXAConnection logicalConnection;
        
        void setLogicalConnection(PGXAConnection logicalConnection) {
            this.logicalConnection = logicalConnection;
        }
        
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Get and peg a physical connection to a backend, if we're in a 
            // start / end, then we'll have an association in place, otherwise
            // we'll get a non-transactional (one-phase) resource back.
            PhysicalXAConnection physicalConnection = getPhysicalConnection(logicalConnection);
                        
            // If the physical connection is servicing an Xid (between start / end, or suspended)
            if (physicalConnection.getAssociatedXid() != null) {
                String methodName = method.getName();
                if (methodName.equals("commit") ||
                    methodName.equals("rollback") ||
                    methodName.equals("setSavePoint") ||
                    (methodName.equals("setAutoCommit") && ((Boolean) args[0]).booleanValue()))
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
