/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3g;

import java.util.Map;
import java.util.Properties;
import java.sql.SQLException;

import org.postgresql.util.HostSpec;

/**
 * This class implements the java.sql.Connection interface for JDBC3.
 * However most of the implementation is really done in
 * org.postgresql.jdbc3.AbstractJdbc3Connection or one of it's parents
 */
public class Jdbc3gConnection extends org.postgresql.jdbc3g.AbstractJdbc3gConnection implements java.sql.Connection
{
    public Jdbc3gConnection(HostSpec[] hostSpecs, String user, String database, Properties info, String url) throws SQLException {
        super(hostSpecs, user, database, info, url);
    }

    public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        checkClosed();
        Jdbc3gStatement s = new Jdbc3gStatement(this, resultSetType, resultSetConcurrency, resultSetHoldability);
        s.setPrepareThreshold(getPrepareThreshold());
        return s;
    }


    public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        checkClosed();
        Jdbc3gPreparedStatement s = new Jdbc3gPreparedStatement(this, sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        s.setPrepareThreshold(getPrepareThreshold());
        return s;
    }

    public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        checkClosed();
        Jdbc3gCallableStatement s = new Jdbc3gCallableStatement(this, sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        s.setPrepareThreshold(getPrepareThreshold());
        return s;
    }

    public java.sql.DatabaseMetaData getMetaData() throws SQLException
    {
        checkClosed();
        if (metadata == null)
            metadata = new Jdbc3gDatabaseMetaData(this);
        return metadata;
    }

    public void setTypeMap(Map < String, Class < ? >> map) throws SQLException
    {
        setTypeMapImpl(map);
    }

}
