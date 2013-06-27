package org.postgresql.xa;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import org.postgresql.core.BaseConnection;

/**
 * XA-enabled DataSource implementation.
 *
 * @author Heikki Linnakangas (heikki.linnakangas@iki.fi)
 */
public class PGXADataSource
    extends AbstractPGXADataSource
    implements XADataSource
{
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
        Connection con = super.getConnection(user, password);
        return new PGXAConnection((BaseConnection) con);
    }
}
