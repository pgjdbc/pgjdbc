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
import java.util.Vector;
import org.postgresql.core.*;

abstract class AbstractJdbc4ResultSet extends org.postgresql.jdbc3.AbstractJdbc3ResultSet
{
    AbstractJdbc4ResultSet(Query originalQuery, BaseStatement statement, Field[] fields, Vector tuples, ResultCursor cursor,
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

    public NClob getNClob(int columnIndex) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getNClob(int)");
    }

    public NClob getNClob(String columnName) throws SQLException
    {
        return getNClob(findColumn(columnName));
    }

    public SQLXML getSQLXML(int columnIndex) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getSQLXML(int)");
    }

    public SQLXML getSQLXML(String columnName) throws SQLException
    {
        return getSQLXML(findColumn(columnName));
    }

    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "updateSQLXML(int, SQLXML)");
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

    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "isWrapperFor(Class<?>)");
    }

    public Object unwrap(Class<?> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "unwrap(Class<?>)");
    }

}

