/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2013, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package org.postgresql.xa.jdbc3;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.naming.Referenceable;
import javax.naming.Reference;
import javax.sql.XAConnection;
import javax.transaction.xa.Xid;
import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.ProtocolConnection;

import org.postgresql.xa.*;

import org.postgresql.ds.PGPooledConnection;
import org.postgresql.ds.common.BaseDataSource;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

/**
 * XA-enabled DataSource implementation.
 *
 * @author Heikki Linnakangas (heikki.linnakangas@iki.fi)
 * @author Bryan Varner (bvarner@polarislabs.com)
 */
public class AbstractJdbc3XADataSource extends BaseDataSource implements Referenceable {
    /**
     * Unsorted collection of physicalConnections to the backend source.
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
    final Map<Long, PhysicalXAConnection> logicalMappings = 
            Collections.synchronizedMap(new HashMap<Long, PhysicalXAConnection>());
    
    final private AtomicLong nextLogicalId = new AtomicLong(0);
    
    /**
     * Gets a connection to the PostgreSQL database.  The database is identified by the
     * DataSource properties serverName, databaseName, and portNumber. The user to
     * connect as is identified by the DataSource properties user and password.
     *
     * @return A valid database connection.
     * @throws SQLException
     *     Occurs when the database connection cannot be established.
     */
    public XAConnection getXAConnection() throws SQLException
    {
        return getXAConnection(getUser(), getPassword());
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
    public XAConnection getXAConnection(String user, String password) throws SQLException
    {
        // Allocate and add a new physical connection to service the front-end
        // XAConnections we return.
        physicalConnections.add(new PhysicalXAConnection((BaseConnection)super.getConnection(user, password)));
        
        // Create a new LogicalXAConnectionHandler proxy.
        LogicalXAConnectionHandler logicalConnection = new LogicalXAConnectionHandler();
        return new PGXAConnection(
                (Connection)Proxy.newProxyInstance(getClass().getClassLoader(), 
                        new Class[]{Connection.class, PGConnection.class}, 
                        logicalConnection),
                new PGXAResource(logicalConnection.getLogicalConnectionId(), user, this));
    }
    
    public String getDescription() {
        return "JDBC3 XA-enabled DataSource from " + org.postgresql.Driver.getVersion();
    }

    /**
     * Generates a reference using the appropriate object factory.
     */
    protected Reference createReference() {
        return new Reference(
                   getClass().getName(),
                   PGXADataSourceFactory.class.getName(),
                   null);
    }
    
    
    private void associateXALink(final Long logicalConnectionId, final PhysicalXAConnection backend) {
        synchronized (logicalMappings) {
            logicalMappings.put(logicalConnectionId, backend);
            logicalMappings.notify();
        }
    }
    
    
    private void disassociateXALink(final Long logicalConnectionId) {
        synchronized (logicalMappings) {
            logicalMappings.remove(logicalConnectionId);
            logicalMappings.notify();
        }
    }
    
    /**
     * Handles invoking Connection and PGConnection upon a physical backend, 
     * based upon the current state of things managed by the XAResource i
     */
    private class LogicalXAConnectionHandler implements InvocationHandler {
        
        long logicalConnectionId;
        
        public LogicalXAConnectionHandler() {
            this.logicalConnectionId = nextLogicalId.getAndIncrement();
        }

        public long getLogicalConnectionId() {
            return logicalConnectionId;
        }
        
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Determine what physical connection to target.
            PhysicalXAConnection physicalConnection = null;
            
            // The XAResource will peg logicalIds to the backend in the map for
            // logical connections servicing an xid.
            physicalConnection = logicalMappings.get(logicalConnectionId);

            // The XAResource has not pegged the logical front-end to an xid 
            // associated backend. Look through the list of available backends
            // without an associatedXid, and currently 'idle' according to 
            // PGConnection's getTransactionState(). (no pending rollback, no current tx).
            if (physicalConnection == null) {
                do {
                    // We're going to try to map this logical connection to a new
                    // physical backend. Lock the mapping to serialize access.
                    synchronized (logicalMappings) {
                        synchronized (physicalConnections) {
                            for (int i = 0; i < physicalConnections.size(); i++) {
                                physicalConnection = physicalConnections.get(i);
                                if (physicalConnection.getAssociatedXid() == null && 
                                    !physicalConnection.isActive()) 
                                {
                                    break;
                                } else {
                                    physicalConnection = null;
                                }
                            }
                        }
                        // We found an available physical connection!
                        if (physicalConnection != null) {
                            associateXALink(logicalConnectionId, physicalConnection);
                        } else {
                            // Oh Noes! We didn't have an xid pegged physical connection for
                            // this logical connection, and there weren't any physical 
                            // connections available to assign to this logical connection!
                            // 
                            // A few things could have happened here.
                            // 1.) We have all our backends pegged to an xid, and this is
                            //     is an attempt to use a non-2pc XAConnection outside of a 
                            //     start / end block (allowable! by the JTA spec!)
                            // 2.) We have all our backends pegged to an xid, and we've been
                            //     asked to do something to another xid which we don't have
                            //     and active backend for. (recovery?)
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
                            // There are a few options we could take here.
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

                            // So, let's block until the logicalMappings get updated again.
                            logicalMappings.wait();
                        }
                    }
                } while (physicalConnection == null);
            }
            
            // If the physical connection is betwen a start / end block...
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
                return method.invoke(physicalConnection, args);
            } catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        }
        
        @Override
        public int hashCode() {
            int hash = 7;
            hash = 29 * hash + (int) (this.logicalConnectionId ^ (this.logicalConnectionId >>> 32));
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final LogicalXAConnectionHandler other = (LogicalXAConnectionHandler) obj;
            if (this.logicalConnectionId != other.logicalConnectionId) {
                return false;
            }
            return true;
        }
    }
    
    
    /**
     * Internal state holder used to track and locate physical backends
     */
    private static class PhysicalXAConnection {
        private BaseConnection backend;
        private Xid associatedXid;
        private PGPooledConnection pooledConnection;

        public PhysicalXAConnection(BaseConnection physicalConn) {
            backend = physicalConn;
            associatedXid = null;
            pooledConnection = new PGPooledConnection(backend, true, true);
        }
        
        public PGPooledConnection getPooledConnection() {
            return pooledConnection;
        }

        public Xid getAssociatedXid() {
            return associatedXid;
        }

        public void setAssociatedXid(Xid associatedXid) {
            this.associatedXid = associatedXid;
        }
        
        public boolean isActive() {
            return backend.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE;
        }
        
        public int getBackendPID() {
            return backend.getBackendPID();
        }
    }
}
