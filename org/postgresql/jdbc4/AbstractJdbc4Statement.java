/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL$
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.sql.*;

import java.io.Reader;
import java.io.InputStream;

abstract class AbstractJdbc4Statement extends org.postgresql.jdbc3.AbstractJdbc3Statement
{

    private boolean poolable;

    AbstractJdbc4Statement (Jdbc4Connection c, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(c, rsType, rsConcurrency, rsHoldability);
        poolable = true;
    }

    public AbstractJdbc4Statement(Jdbc4Connection connection, String sql, boolean isCallable, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(connection, sql, isCallable, rsType, rsConcurrency, rsHoldability);
    }

    public boolean isClosed() throws SQLException
    {
        return isClosed;
    }

    public void setRowId(int parameterIndex, RowId x) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setRowId(int, RowId)");
    }

    public void setNString(int parameterIndex, String value) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setNString(int, String)");
    }

    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setNCharacterStream(int, Reader, long)");
    }

    public void setNClob(int parameterIndex, NClob value) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setNClob(int, NClob)");
    }

    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setClob(int, Reader, long)");
    }

    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setBlob(int, InputStream, long)");
    }

    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setNClob(int, Reader, long)");
    }

    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setSQLXML(int, SQLXML)");
    }

    public void setPoolable(boolean poolable) throws SQLException
    {
        checkClosed();
        this.poolable = poolable;
    }

    public boolean isPoolable() throws SQLException
    {
        checkClosed();
        return poolable;
    }

    public RowId getRowId(int parameterIndex) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getRowId(int)");
    }

    public RowId getRowId(String parameterName) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getRowId(String)");
    }

    public void setRowId(String parameterName, RowId x) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setRowId(String, RowId)");
    }
    
    public void setNString(String parameterName, String value) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setNString(String, String)");
    }

    public void setNCharacterStream(String parameterName, Reader value, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setNCharacterStream(String, Reader, long)");
    }

    public void setNClob(String parameterName, NClob value) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setNClob(String, NClob)");
    }

    public void setClob(String parameterName, Reader reader, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setClob(String, Reader, long)");
    }

    public void setBlob(String parameterName, InputStream inputStream, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setBlob(String, InputStream, long)");
    }

    public void setNClob(String parameterName, Reader reader, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setNClob(String, Reader, long)");
    }

    public NClob getNClob(int parameterIndex) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getNClob(int)");
    }

    public NClob getNClob(String parameterName) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getNClob(String)");
    }

    public void setSQLXML(String parameterName, SQLXML xmlObject) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setSQLXML(String, SQLXML)");
    }

    public SQLXML getSQLXML(int parameterIndex) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getSQLXML(int)");
    }

    public SQLXML getSQLXML(String parameterIndex) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getSQLXML(String)");
    }

    public String getNString(int parameterIndex) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getNString(int)");
    }

    public String getNString(String parameterName) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getNString(String)");
    }

    public Reader getNCharacterStream(int parameterIndex) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getNCharacterStream(int)");
    }

    public Reader getNCharacterStream(String parameterName) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getNCharacterStream(String)");
    }

    public Reader getCharacterStream(int parameterIndex) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getCharacterStream(int)");
    }

    public Reader getCharacterStream(String parameterName) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getCharacterStream(String)");
    }

    public void setBlob(String parameterName, Blob x) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setBlob(String, Blob)");
    }

    public void setClob(String parameterName, Clob x) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setClob(String, Clob)");
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "isWrapperFor(Class<?>)");
    }

    public Object unwrap(Class<?> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "unwrap(Class<?>)");
    }

}
