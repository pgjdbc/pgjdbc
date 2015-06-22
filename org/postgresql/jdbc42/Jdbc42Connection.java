/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc42;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.util.Properties;

import org.postgresql.util.HostSpec;

public class Jdbc42Connection extends AbstractJdbc42Connection
{

    public Jdbc42Connection(HostSpec[] hostSpecs, String user, String database, Properties info, String url) throws SQLException
    {
        super(hostSpecs, user, database, info, url);
    }

    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        checkClosed();
        return new Jdbc42Statement(this, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        checkClosed();
        return new Jdbc42PreparedStatement(this, sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        checkClosed();
        return new Jdbc42CallableStatement(this, sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    public DatabaseMetaData getMetaData() throws SQLException
    {
        checkClosed();
        if (metadata == null)
        {
            metadata = new Jdbc42DatabaseMetaData(this);
        }
        return metadata;
    }

    public void setTypeMap(java.util.Map<String,java.lang.Class<?>> map) throws SQLException
    {
        setTypeMapImpl(map);
    }

    protected Array makeArray(int oid, String fieldString) throws SQLException
    {
        return new Jdbc42Array(this, oid, fieldString);
    }

    protected Blob makeBlob(long oid) throws SQLException
    {
        return new Jdbc42Blob(this, oid);
    }

    protected Clob makeClob(long oid) throws SQLException
    {
        return new Jdbc42Clob(this, oid);
    }

    protected SQLXML makeSQLXML() throws SQLException
    {
        return new Jdbc42SQLXML(this);
    }
}
