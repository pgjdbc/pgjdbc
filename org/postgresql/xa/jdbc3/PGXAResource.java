/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2013, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package org.postgresql.xa.jdbc3;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

/**
 *
 * @author Bryan Varner (bvarner@polarislabs.com)
 */
public class PGXAResource implements XAResource {
    private long logicalConnectionId;
    private String user;
    private AbstractJdbc3XADataSource dataSource;
    

    /**
     * Constructs an XAResource representing the given logicalConnectionId from
     * the given dataSource (Resource). 
     * 
     * This allows us to manage the dataSource's internal mapping between 
     * logical front-end connections and back-end connections in a shareable,
     * thread-safe, JTA compliant (complete with interleaving!) manner.
     * 
     * @param logicalConnectionId
     * @param user
     * @param dataSource 
     */
    public PGXAResource(final long logicalConnectionId, final String user, final AbstractJdbc3XADataSource dataSource) {
        this.logicalConnectionId = logicalConnectionId;
        this.user = user;
        this.dataSource = dataSource;
    }
    
    public void start(Xid xid, int i) throws XAException {
        
    }
    
    public void end(Xid xid, int i) throws XAException {
        
    }

    /**
     * @param xid
     * @throws XAException 
     */
    public void forget(Xid xid) throws XAException {
        
    }

    public void commit(Xid xid, boolean bln) throws XAException {
        
    }


    public boolean isSameRM(XAResource otherRm) throws XAException {
        if (otherRm == this) {
            return true;
        }
        
        // This is going to tell the TM that we can handle interleaving.
        if (otherRm instanceof PGXAResource) {
            // we need to make sure that the dataSource is to the same server/port/username as the XAConnection we were created alongside.
            PGXAResource other = (PGXAResource)otherRm;
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

    public int prepare(Xid xid) throws XAException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public Xid[] recover(int i) throws XAException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void rollback(Xid xid) throws XAException {
    }

    public int getTransactionTimeout() throws XAException {
        return 0; // We don't support this.
    }
    
    public boolean setTransactionTimeout(int timeout) throws XAException {
        return false; // We don't support this.
    }
}
