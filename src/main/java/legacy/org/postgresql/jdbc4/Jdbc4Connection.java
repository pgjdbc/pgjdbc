/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc4;

import java.util.Map;
import java.util.Properties;
import java.sql.SQLException;

/**
 * This class implements the java.sql.Connection interface for JDBC4.
 * However most of the implementation is really done in
 * org.postgresql.jdbc4.AbstractJdbc4Connection or one of it's parents
 */
public class Jdbc4Connection extends AbstractJdbc4Connection implements java.sql.Connection
{
    public Jdbc4Connection(String host, int port, String user, String database, Properties info, String url) throws SQLException {
        super(host, port, user, database, info, url);
    }

    public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        checkClosed();
        Jdbc4Statement s = new Jdbc4Statement(this, resultSetType, resultSetConcurrency, resultSetHoldability);
        s.setPrepareThreshold(getPrepareThreshold());
        return s;
    }


    public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        checkClosed();
        Jdbc4PreparedStatement s = new Jdbc4PreparedStatement(this, sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        s.setPrepareThreshold(getPrepareThreshold());
        return s;
    }

    public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        checkClosed();
        Jdbc4CallableStatement s = new Jdbc4CallableStatement(this, sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        s.setPrepareThreshold(getPrepareThreshold());
        return s;
    }

    public java.sql.DatabaseMetaData getMetaData() throws SQLException
    {
        checkClosed();
        if (metadata == null)
            metadata = new Jdbc4DatabaseMetaData(this);
        return metadata;
    }

    public void setTypeMap(Map < String, Class < ? >> map) throws SQLException
    {
        setTypeMapImpl(map);
    }

}
