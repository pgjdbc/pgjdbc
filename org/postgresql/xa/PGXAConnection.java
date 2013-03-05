/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2011, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package org.postgresql.xa;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.LinkedList;
import javax.sql.*;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import org.postgresql.PGConnection;
import org.postgresql.ds.PGPooledConnection;
import org.postgresql.util.GT;

/**
 * The PostgreSQL implementation of {@link XAResource}.
 * 
 * Two-phase commit requires PostgreSQL server version 8.1
 * or higher.
 * 
 * This implementation constitutes a 'Transactional Resource' in the JTA 1.0.1
 * specification. As such, it's an amalgamation of an XAConnection, which uses
 * connection pool semantics atop a 'logical' (or virtual) Connection to the 
 * database, which is backed by a real, 'physical' Connection maintained within
 * the XADataSource implementation. The implementation of XAResource provided 
 * by this implementation is thread-safe, shareable (amongst equivalent Resources),
 * and implements the required functionality for transaction interleaving.
 * 
 * @author Heikki Linnakangas (heikki.linnakangas@iki.fi)
 * @author Bryan Varner (bvarner@polarislabs.com)
 */
public class PGXAConnection extends PGPooledConnection implements XAConnection, XAResource {

    private String user;
    private String password;
    private PGXADataSource dataSource;
    
    private PhysicalXAConnection backend;
    private boolean closeHandleInProgress;
    
    protected PGXAConnection(final String user, final String password, final Connection logicalConnection, PGXADataSource dataSource) {
        super(logicalConnection, true, true);
        this.user = user;
        this.password = password;
        this.dataSource = dataSource;
        this.backend = null;
        this.closeHandleInProgress = false;
    }
    
    /**
     * Returns a handle to the logical connection (we are pooled, remember)
     * 
     * @return Connection - this is a proxy of a Connection used as the logical connection
     * @throws SQLException 
     */
    @Override
    public Connection getConnection() throws SQLException {
        Connection handle = super.getConnection();
        this.closeHandleInProgress = false;
        
        return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{Connection.class, PGConnection.class},
                new XAConnectionHandler(handle));
    }

    @Override
    public XAResource getXAResource() {
        return this;
    }
    
    @Override
    public synchronized void close() throws SQLException {
        SQLException sqle = null;
        try {
            // If there is a handle open, the super.close() will invoke close() on the handle.
            this.closeHandleInProgress = true;
            super.close();
        } catch (SQLException closeEx) {
            sqle = closeEx;
            
            // If we had an exception on close, and there's no closable connections (which was likely the cause!)
            // Clear the exception and don't throw it.
            if (dataSource != null && dataSource.getCloseableConnectionCount(this) == 0) {
                sqle = null;
            }
        } finally {
            if (dataSource != null) {
                dataSource.close(this);
            }
        }

        dataSource = null;
        
        if (sqle != null) {
            throw sqle;
        }
    }

    /**** XAResource interface ****/
    /**
     * Preconditions:
     * 1. flags must be one of TMNOFLAGS, TMRESUME or TMJOIN
     * 2. xid != null
     * 3. Logical connection must not be associated with another backend
     * 4. if TMNOFLAGS, the RM hasn't seen the xid before.
     *
     * Postconditions:
     * 1. Connection is associated with the transaction
     * 
     * @param xid The transaction id
     * @param flags flags to the transaction start (TMNOFLAGS, TMRESUME, or TMJOIN)
     * @throws XAException 
     */
    public synchronized void start(Xid xid, int flags) throws XAException {
        if (xid == null) {
            throw new PGXAException(GT.tr("xid must not be null"), XAException.XAER_INVAL);
        }

        switch (flags) {
            case XAResource.TMNOFLAGS:
                try {
                    dataSource.start(this, xid);
                } catch (PGXAException pgae) {
                    throw pgae;
                } catch (XAException xae) {
                    throw new PGXAException(GT.tr(xae.getMessage()), xae, XAException.XAER_DUPID);
                }
                break;
            case XAResource.TMRESUME:
                // Associate the logicalConnectionId with an existing, pegged physical backend in the suspended state.
                try {
                    dataSource.resume(this, xid);
                } catch (PGXAException pgae) {
                    throw pgae;
                } catch (XAException xae) {
                    throw new PGXAException(GT.tr(xae.getMessage()), xae, XAException.XAER_INVAL);
                }

                break;
            case XAResource.TMJOIN:
                // Associate the logicalConnectionId with an existing, pegged physical backend not in the suspended state.
                try {
                    dataSource.join(this, xid);
                } catch (PGXAException pgae) {
                    throw pgae;
                } catch (XAException xae) {
                    throw new PGXAException(GT.tr(xae.getMessage()), xae, XAException.XAER_INVAL);
                }

                break;
            default:
                throw new PGXAException(GT.tr("Invalid flags"), XAException.XAER_INVAL);
        }
    }
    
    /**
     * Preconditions:
     * 1. Flags is one of TMSUCCESS, TMFAIL, TMSUSPEND
     * 2. xid != null
     * 3. Logical Connection is associated with physical backend servicing xid
     *
     * Postconditions:
     * 1. connection is disassociated from the transaction.
     * 
     * @param xid The transaction id
     * @param flags flags for the end call (TMSUCCESS, TMFAIL, TMSUSPEND)
     * @throws XAException 
     */
    public synchronized void end(Xid xid, int flags) throws XAException {
        if (flags != XAResource.TMSUSPEND && flags != XAResource.TMFAIL && flags != XAResource.TMSUCCESS) {
            throw new PGXAException(GT.tr("Invalid flags"), XAException.XAER_INVAL);
        }

        if (xid == null) {
            throw new PGXAException(GT.tr("xid must not be null"), XAException.XAER_INVAL);
        }

        // TMSUSPEND, TMSUCCESS, TMFAIL are more or less hints to this resource manager. 
        // We don't _have_ to do anything special to handle them, although we could rollback immediately (if we wanted) 
        // in the case of of TMFAIL.
        
        if (flags == XAResource.TMSUSPEND) {
            dataSource.suspend(this, xid);
        }
        dataSource.end(this, xid);
    }

    /**
     * While the PG server doesn't really do heuristics, it's possible for a
     * sysadmin to do a manual rollback / commit of a prepared transaction, 
     * effectively performing a heuristic decision, and leaving a backend 
     * physical connection in the dataSource pegged to the now-defuct Xid.
     * 
     * For that reason, we're implementing this method.
     * 
     * @param xid the transaction id
     * @throws XAException 
     */
    public void forget(Xid xid) throws XAException {
        dataSource.forget(xid);
    }

    /**
     * 
     * @param xid the transaction id
     * @param onePhase true if this is a one-phase commit, false if two
     * @throws XAException 
     */
    public synchronized void commit(Xid xid, boolean onePhase) throws XAException {
        if (xid == null) {
            throw new PGXAException(GT.tr("xid must not be null"), XAException.XAER_INVAL);
        }

        dataSource.commit(this, xid, onePhase);
    }
    
    /**
     * 
     * @param xares The xa resource to compare
     * @return true if the serverName, portNumber, databaseName, and user are equal; false otherwise.
     * @throws XAException 
     */
    public boolean isSameRM(XAResource xares) throws XAException {
        if (xares == this) {
            return true;
        }
        
        // This is going to tell the TM that we can handle interleaving.
        if (xares instanceof PGXAConnection) {
            // we need to make sure that the dataSource is to the same server/port/username as the XAConnection we were created alongside.
            PGXAConnection other = (PGXAConnection)xares;
            if (other.dataSource.getServerName().equals(dataSource.getServerName()) &&
                other.dataSource.getPortNumber() == dataSource.getPortNumber() &&
                other.dataSource.getDatabaseName().equals(dataSource.getDatabaseName()) &&
                other.user.equals(user))
            {
                return true; // We're the same name, port, db, and user.
            }
        }
        
        return false;
    }

    /**
     * Preconditions:
     * 1. xid != null
     * 2. A physical connection is pegged to the Xid, is not suspended, and is not associated to a logical connection.
     *
     * Postconditions:
     * 1. Transaction is prepared, and the physical backend which it was associated to is left unsuspended
     * 
     * @param xid The transaction id
     * @return XAResource.XA_OK if all went well. This does not track queries in order to return XA_RDONLY yet
     * @throws XAException 
     */
    public int prepare(Xid xid) throws XAException {
        try {
            return dataSource.prepare(this, xid);
        } catch (Exception ex) {
            throw new PGXAException(GT.tr(ex.getMessage()), ex, XAException.XAER_RMERR);
        }
    }

    /**
     * Preconditions:
     * 1. flag must be one of TMSTARTRSCAN, TMENDRSCAN, TMNOFLAGS or TMSTARTTRSCAN | TMENDRSCAN
     * 2. if flag isn't TMSTARTRSCAN or TMSTARTRSCAN | TMENDRSCAN, a recovery scan must be in progress
     *
     * Postconditions:
     * 1. list of prepared xids is returned
     * 
     * @param flag Must be one of TMSTARTRSCAN, TMENDRSCAN, TMNOFLAGS or TMSTARTTRSCAN | TMENDRSCAN
     * @return Array of transaction ids that were successfully recovered
     * @throws XAException 
     */
    public Xid[] recover(int flag) throws XAException {
        // Check preconditions
        if (flag != TMSTARTRSCAN && flag != TMENDRSCAN && flag != TMNOFLAGS && flag != (TMSTARTRSCAN | TMENDRSCAN)) {
            throw new PGXAException(GT.tr("Invalid flag"), XAException.XAER_INVAL);
        }

        // We don't check for precondition 2, because we would have to add some additional state in
        // this object to keep track of recovery scans.
        
        // All clear. We return all the xids in the first TMSTARTRSCAN call, and always return
        // an empty array otherwise.
        if ((flag & TMSTARTRSCAN) == 0) {
            return new Xid[0];
        } else {
            ResultSet rs = null;
            Statement stmt = null;
            try {
                // TODO: Reconcile this method against the JTA spec. It seems very incorrect at this point, although I don't believe
                // you can hold a cursor in a prepared transaction, so this may be a case of needing an extra 'control' connection.
                stmt = dataSource.resolvePhysicalConnection(this, null).getConnection().createStatement();
                
                // If this connection is simultaneously used for a transaction,
                // this query gets executed inside that transaction. It's OK,
                // except if the transaction is in abort-only state and the
                // backed refuses to process new queries. Hopefully not a problem
                // in practise.
                rs = stmt.executeQuery("SELECT gid FROM pg_prepared_xacts");
                LinkedList<Xid> l = new LinkedList<Xid>();
                while (rs.next()) {
                    Xid recoveredXid = RecoveredXid.stringToXid(rs.getString(1));
                    if (recoveredXid != null) {
                        l.add(recoveredXid);
                    }
                }

                return l.toArray(new Xid[l.size()]);
            } catch (SQLException ex) {
                throw new PGXAException(GT.tr("Error during recover"), ex, XAException.XAER_RMERR);
            } finally {
                if (rs != null) {
                    try {
                        rs.close();
                    } catch (SQLException sqle) {
                        throw new PGXAException(GT.tr("Error during recover"), sqle, XAException.XAER_RMERR);
                    }
                }
                
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (SQLException sqle) { // Fail quietly.
                        throw new PGXAException(GT.tr("Error during recover"), sqle, XAException.XAER_RMERR);
                    }
                }
            }
        }
    }

    /**
     * @see PGXADataSource#rollback(PGXAConnection, javax.transaction.xa.Xid)
     *
     * @param xid The transaction id
     * @throws XAException 
     */
    public synchronized void rollback(Xid xid) throws XAException {
        dataSource.rollback(this, xid);
    }

    /**
     * Not supported for the PostgreSQL XA Implementation.
     * 
     * @return 0 - This feature is not currently supported
     * @throws XAException 
     */
    public int getTransactionTimeout() throws XAException {
        return 0; // We don't support this.
    }
    
    /**
     * Not supported for the PostgreSQL XA Implementation.
     * 
     * @param seconds ignored - This feature is not currently supported
     * @return false - This feature is not currently supported
     * @throws XAException 
     */
    public boolean setTransactionTimeout(int seconds) throws XAException {
        return false; // We don't support this.
    }

    /**
     * Used by the PGXADataSource to make sure we're getting a proper physical connection.
     * 
     * @return the user specified when the XAConnection was created.
     */
    String getUser() {
        return user;
    }

    /**
     * Used by the PGXADataSource to make sure we're getting a proper physical connection.
     * 
     * @return the password specified when the XAConnection was created.
     */
    String getPassword() {
        return password;
    }
    
    /**
     * Managed by the PhysicalXAConnection's associate / disassociate methods.
     */
    PhysicalXAConnection getPhysicalXAConnection() {
        return backend;
    }
    
    /**
     * Managed by the PhysicalXAConnection's associate / disassociate methods.
     */
    void setPhysicalXAConnection(PhysicalXAConnection backend) {
        this.backend = backend;
    }

    /**
     * Determines if a Connection handle close() is in progress or not.
     * 
     * @return true if a Connection.close() invocation is being serviced.
     */
    boolean isCloseHandleInProgress() {
        return closeHandleInProgress;
    }
    
    /**
     * Wrap the pooled handle in a proxy allowing special behavior in the LogicalXAConnectionHandler when close() is being invoked
     * on a handle.
     */
    private class XAConnectionHandler implements InvocationHandler {
        Connection pooledHandle;
        
        private XAConnectionHandler(Connection pooledHandle) {
            this.pooledHandle = pooledHandle;
        }
        
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("close")) {
                closeHandleInProgress = true;
            }
            
            try {
                return method.invoke(pooledHandle, args);
            } catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        }
    }
}
