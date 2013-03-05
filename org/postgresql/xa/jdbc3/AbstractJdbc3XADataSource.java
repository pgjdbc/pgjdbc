/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2013, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package org.postgresql.xa.jdbc3;

import java.sql.SQLException;

import javax.naming.Referenceable;
import javax.naming.Reference;
import javax.sql.XAConnection;

import org.postgresql.xa.*;

import org.postgresql.ds.common.BaseDataSource;

/**
 * XA-enabled DataSource implementation.
 *
 * @author Heikki Linnakangas (heikki.linnakangas@iki.fi)
 * @author Bryan Varner (bvarner@polarislabs.com)
 */
public abstract class AbstractJdbc3XADataSource extends BaseDataSource implements Referenceable {
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
    
    public abstract XAConnection getXAConnection(String user, String password) throws SQLException;
    
    public String getDescription() {
        return "JDBC3 XA-enabled DataSource from " + org.postgresql.Driver.getVersion();
    }

    /**
     * Generates a reference using the appropriate object factory.
     */
    @Override
    protected Reference createReference() {
        return new Reference(
                   getClass().getName(),
                   PGXADataSourceFactory.class.getName(),
                   null);
    }
}
