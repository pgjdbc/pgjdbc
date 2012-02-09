/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;


import java.sql.*;
import java.util.Map;
import java.util.Vector;
import org.postgresql.core.*;

/**
 * This class implements the java.sql.ResultSet interface for JDBC4.
 * However most of the implementation is really done in
 * org.postgresql.jdbc4.AbstractJdbc4ResultSet or one of it's parents
 */
public class Jdbc4ResultSet extends AbstractJdbc4ResultSet implements java.sql.ResultSet
{
    private Jdbc4ResultSetMetaData metaData;

    Jdbc4ResultSet(Query originalQuery, BaseStatement statement, Field[] fields, Vector tuples, ResultCursor cursor,
                    int maxRows, int maxFieldSize, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(originalQuery, statement, fields, tuples, cursor, maxRows, maxFieldSize, rsType, rsConcurrency, rsHoldability);
    }

    public java.sql.ResultSetMetaData getMetaData() throws SQLException
    {
        checkClosed();

        if (metaData == null)
        {
            metaData = new Jdbc4ResultSetMetaData(connection, fields);
        }

        return metaData;
    }

    public java.sql.Clob getClob(int i) throws SQLException
    {
        checkResultSet(i);
        if (wasNullFlag)
            return null;

        return new Jdbc4Clob(connection, getLong(i));
    }

    public java.sql.Blob getBlob(int i) throws SQLException
    {
        checkResultSet(i);
        if (wasNullFlag)
            return null;

        return new Jdbc4Blob(connection, getLong(i));
    }

    public Array createArray(int i) throws SQLException
    {
        checkResultSet(i);
        int oid = fields[i - 1].getOID();
        if (isBinary(i)) {
            return new Jdbc4Array(connection, oid, this_row[i - 1]);
        }
        String value = getFixedString(i);
        return new Jdbc4Array(connection, oid, value);
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

