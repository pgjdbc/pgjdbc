/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3g;


import java.sql.*;
import java.util.List;
import java.util.Map;
import org.postgresql.core.*;

/**
 * This class implements the java.sql.ResultSet interface for JDBC3.
 * However most of the implementation is really done in
 * org.postgresql.jdbc3.AbstractJdbc3ResultSet or one of it's parents
 */
public class Jdbc3gResultSet extends org.postgresql.jdbc3g.AbstractJdbc3gResultSet implements java.sql.ResultSet
{
    Jdbc3gResultSet(Query originalQuery, BaseStatement statement, Field[] fields, List tuples, ResultCursor cursor,
                    int maxRows, int maxFieldSize, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(originalQuery, statement, fields, tuples, cursor, maxRows, maxFieldSize, rsType, rsConcurrency, rsHoldability);
    }

    protected java.sql.ResultSetMetaData createMetaData() throws SQLException
    {
        return new Jdbc3gResultSetMetaData(connection, fields);
    }

    protected java.sql.Clob makeClob(long oid) throws SQLException
    {
        return new Jdbc3gClob(connection, oid);
    }

    protected java.sql.Blob makeBlob(long oid) throws SQLException
    {
        return new Jdbc3gBlob(connection, oid);
    }

    public Array makeArray(int oid, byte[] value) throws SQLException
    {
        return new Jdbc3gArray(connection, oid, value);
    }

    public Array makeArray(int oid, String value) throws SQLException
    {
        return new Jdbc3gArray(connection, oid, value);
    }

    public Object getObject(String s, Map < String, Class < ? >> map) throws SQLException
    {
        return getObjectImpl(s, map);
    }

    public Object getObject(int i, Map < String, Class < ? >> map) throws SQLException
    {
        return getObjectImpl(i, map);
    }

}

