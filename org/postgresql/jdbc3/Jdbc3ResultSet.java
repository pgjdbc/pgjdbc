/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3;


import java.sql.*;
import java.util.List;
import java.util.Map;
import org.postgresql.core.*;

/**
 * This class implements the java.sql.ResultSet interface for JDBC3.
 * However most of the implementation is really done in
 * org.postgresql.jdbc3.AbstractJdbc3ResultSet or one of it's parents
 */
public class Jdbc3ResultSet extends org.postgresql.jdbc3.AbstractJdbc3ResultSet implements java.sql.ResultSet
{
    Jdbc3ResultSet(Query originalQuery, BaseStatement statement, Field[] fields, List tuples, ResultCursor cursor,
                   int maxRows, int maxFieldSize, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(originalQuery, statement, fields, tuples, cursor, maxRows, maxFieldSize, rsType, rsConcurrency, rsHoldability);
    }

    protected java.sql.ResultSetMetaData createMetaData() throws SQLException
    {
        return new Jdbc3ResultSetMetaData(connection, fields);
    }

    protected java.sql.Clob makeClob(long oid) throws SQLException
    {
        return new Jdbc3Clob(connection, oid);
    }

    protected java.sql.Blob makeBlob(long oid) throws SQLException
    {
        return new Jdbc3Blob(connection, oid);
    }

    protected Array makeArray(int oid, byte[] value) throws SQLException
    {
        return new Jdbc3Array(connection, oid, value);
    }

    protected Array makeArray(int oid, String value) throws SQLException
    {
        return new Jdbc3Array(connection, oid, value);
    }

    public Object getObject(String s, Map map) throws SQLException
    {
        return getObjectImpl(s, map);
    }

    public Object getObject(int i, Map map) throws SQLException
    {
        return getObjectImpl(i, map);
    }

}

