/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.util.Map;
import java.util.Properties;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.SQLException;
import java.sql.SQLXML;

import org.postgresql.core.BaseConnection;
import org.postgresql.util.HostSpec;

/**
 * This class implements the java.sql.Connection interface for JDBC4.
 * However most of the implementation is really done in
 * org.postgresql.jdbc4.AbstractJdbc4Connection or one of it's parents
 */
public class Jdbc4Connection extends AbstractJdbc4Connection implements java.sql.Connection
{
    public Jdbc4Connection(HostSpec[] hostSpecs, String user, String database, Properties info, String url) throws SQLException {
        super(hostSpecs, user, database, info, url);
    }

    public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        checkClosed();
        return new Jdbc4Statement(this, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        checkClosed();
        return new Jdbc4PreparedStatement(this, sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        checkClosed();
        return new Jdbc4CallableStatement(this, sql, resultSetType, resultSetConcurrency, resultSetHoldability);
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

    protected Array makeArray(int oid, String fieldString)
        throws SQLException
    {
        return new Jdbc4Array(this, oid, fieldString);
    }

    protected Blob makeBlob(long oid) throws SQLException
    {
        return new Jdbc4Blob(this, oid);
    }

    protected Clob makeClob(long oid) throws SQLException
    {
        return new Jdbc4Clob(this, oid);
    }

    protected SQLXML makeSQLXML() throws SQLException
    {
        return new Jdbc4SQLXML(this);
    }

}
