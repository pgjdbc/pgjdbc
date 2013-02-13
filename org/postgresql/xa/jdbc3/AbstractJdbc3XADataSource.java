/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2011, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package org.postgresql.xa.jdbc3;

import java.sql.SQLException;

import javax.naming.Referenceable;
import javax.naming.Reference;
import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;

import org.postgresql.xa.*;

import org.postgresql.ds.PGPooledConnection;
import org.postgresql.ds.common.BaseDataSource;

/**
 * XA-enabled DataSource implementation.
 *
 * @author Heikki Linnakangas (heikki.linnakangas@iki.fi)
 * @author Bryan Varner (bvarner@polarislabs.com)
 */
public class AbstractJdbc3XADataSource extends BaseDataSource implements Referenceable
{
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
     * Gets a XA-enabled connection to the PostgreSQL database.  The database is identified by the
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
        // This is a physical connection.
        PGPooledConnection physicalConnection = new PGPooledConnection(super.getConnection(user, password), true, true);
        
        // Return a PGXAConnection which references this AbstractJdbc3XADataSource.
        // This data sourc will be responsible for tracking in-use physical connections.
        // For each discrete XAConnection returned from the data source, we will have a managed physical connection.
        // The physical connection may be associated to one xid at a time.
        // A physical connection in use by an xid may not be used outside the scope of an XA start / end block.
        
        // The returned object (XAConnection) is a 'logical' connection. At least one physical connection will be bound
        // to the life-cycle of the returned XAConnection. However... The physical connection underlying a logical
        // connection may be swapped out by the XAResource manager.
        
        return new PGXAConnection(physicalConnection, this);
    }
    
    public XAResource getXAResource() {
        return null;
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
}
