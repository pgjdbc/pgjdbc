/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;


import java.sql.*;
import java.io.Reader;
import java.io.InputStream;
import java.util.List;
import org.postgresql.core.*;

abstract class AbstractJdbc4ResultSet extends org.postgresql.jdbc3g.AbstractJdbc3gResultSet
{
    AbstractJdbc4ResultSet(Query originalQuery, BaseStatement statement, Field[] fields, List tuples, ResultCursor cursor,
                    int maxRows, int maxFieldSize, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(originalQuery, statement, fields, tuples, cursor, maxRows, maxFieldSize, rsType, rsConcurrency, rsHoldability);
    }

    public RowId getRowId(int columnIndex) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getRowId(int)");
    }

    public RowId getRowId(String columnName) throws SQLException
    {
        return getRowId(findColumn(columnName));
    }

    public void updateRowId(int columnIndex, RowId x) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateRowId(int, RowId)");
    }

    public void updateRowId(String columnName, RowId x) throws SQLException
    {
        updateRowId(findColumn(columnName), x);
    }

    public int getHoldability() throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getHoldability()");
    }

    public boolean isClosed() throws SQLException
    {
        return (rows == null);
    }

    public void updateNString(int columnIndex, String nString) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateNString(int, String)");
    }

    public void updateNString(String columnName, String nString) throws SQLException
    {
        updateNString(findColumn(columnName), nString);
    }

    public void updateNClob(int columnIndex, NClob nClob) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateNClob(int, NClob)");
    }

    public void updateNClob(String columnName, NClob nClob) throws SQLException
    {
        updateNClob(findColumn(columnName), nClob);
    }

    public void updateNClob(int columnIndex, Reader reader) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateNClob(int, Reader)");
    }

    public void updateNClob(String columnName, Reader reader) throws SQLException
    {
        updateNClob(findColumn(columnName), reader);
    }

    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateNClob(int, Reader, long)");
    }

    public void updateNClob(String columnName, Reader reader, long length) throws SQLException
    {
        updateNClob(findColumn(columnName), reader, length);
    }

    public NClob getNClob(int columnIndex) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getNClob(int)");
    }

    public NClob getNClob(String columnName) throws SQLException
    {
        return getNClob(findColumn(columnName));
    }

    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateBlob(int, InputStream, long)");
    }

    public void updateBlob(String columnName, InputStream inputStream, long length) throws SQLException
    {
        updateBlob(findColumn(columnName), inputStream, length);
    }

    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateBlob(int, InputStream)");
    }

    public void updateBlob(String columnName, InputStream inputStream) throws SQLException
    {
        updateBlob(findColumn(columnName), inputStream);
    }

    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateClob(int, Reader, long)");
    }

    public void updateClob(String columnName, Reader reader, long length) throws SQLException
    {
        updateClob(findColumn(columnName), reader, length);
    }

    public void updateClob(int columnIndex, Reader reader) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateClob(int, Reader)");
    }

    public void updateClob(String columnName, Reader reader) throws SQLException
    {
        updateClob(findColumn(columnName), reader);
    }

    public SQLXML getSQLXML(int columnIndex) throws SQLException
    {
        String data = getString(columnIndex);
        if (data == null)
            return null;

        return new Jdbc4SQLXML(connection, data);
    }

    public SQLXML getSQLXML(String columnName) throws SQLException
    {
        return getSQLXML(findColumn(columnName));
    }

    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException
    {
        updateValue(columnIndex, xmlObject);
    }

    public void updateSQLXML(String columnName, SQLXML xmlObject) throws SQLException
    {
        updateSQLXML(findColumn(columnName), xmlObject);
    }

    public String getNString(int columnIndex) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getNString(int)");
    }

    public String getNString(String columnName) throws SQLException
    {
        return getNString(findColumn(columnName));
    }

    public Reader getNCharacterStream(int columnIndex) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getNCharacterStream(int)");
    }

    public Reader getNCharacterStream(String columnName) throws SQLException
    {
        return getNCharacterStream(findColumn(columnName));
    }

    public void updateNCharacterStream(int columnIndex, Reader x, int length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateNCharacterStream(int, Reader, int)");
    }

    public void updateNCharacterStream(String columnName, Reader x, int length) throws SQLException
    {
        updateNCharacterStream(findColumn(columnName), x, length);
    }

    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateNCharacterStream(int, Reader)");
    }

    public void updateNCharacterStream(String columnName, Reader x) throws SQLException
    {
        updateNCharacterStream(findColumn(columnName), x);
    }

    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateNCharacterStream(int, Reader, long)");
    }

    public void updateNCharacterStream(String columnName, Reader x, long length) throws SQLException
    {
        updateNCharacterStream(findColumn(columnName), x, length);
    }

    public void updateCharacterStream(int columnIndex, Reader reader, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateCharaceterStream(int, Reader, long)");
    }

    public void updateCharacterStream(String columnName, Reader reader, long length) throws SQLException
    {
        updateCharacterStream(findColumn(columnName), reader, length);
    }

    public void updateCharacterStream(int columnIndex, Reader reader) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateCharaceterStream(int, Reader)");
    }

    public void updateCharacterStream(String columnName, Reader reader) throws SQLException
    {
        updateCharacterStream(findColumn(columnName), reader);
    }

    public void updateBinaryStream(int columnIndex, InputStream inputStream, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateBinaryStream(int, InputStream, long)");
    }

    public void updateBinaryStream(String columnName, InputStream inputStream, long length) throws SQLException
    {
        updateBinaryStream(findColumn(columnName), inputStream, length);
    }

    public void updateBinaryStream(int columnIndex, InputStream inputStream) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateBinaryStream(int, InputStream)");
    }

    public void updateBinaryStream(String columnName, InputStream inputStream) throws SQLException
    {
        updateBinaryStream(findColumn(columnName), inputStream);
    }

    public void updateAsciiStream(int columnIndex, InputStream inputStream, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateAsciiStream(int, InputStream, long)");
    }

    public void updateAsciiStream(String columnName, InputStream inputStream, long length) throws SQLException
    {
        updateAsciiStream(findColumn(columnName), inputStream, length);
    }

    public void updateAsciiStream(int columnIndex, InputStream inputStream) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateAsciiStream(int, InputStream)");
    }

    public void updateAsciiStream(String columnName, InputStream inputStream) throws SQLException
    {
        updateAsciiStream(findColumn(columnName), inputStream);
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "isWrapperFor(Class<?>)");
    }

    public <T> T unwrap(Class<T> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "unwrap(Class<T>)");
    }

    protected Object internalGetObject(int columnIndex, Field field) throws SQLException
    {
        switch(getSQLType(columnIndex))
        {
            case Types.SQLXML:
                return getSQLXML(columnIndex);
        }
        return super.internalGetObject(columnIndex, field);
    }

    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getObject(int, Class<T>)");
    }

    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException
    {
        return getObject(findColumn(columnLabel), type);
    }

}

