/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/Jdbc2ResultSet.java,v 1.19 2007/12/01 12:50:44 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;


import java.sql.*;
import java.util.Map;
import java.util.Vector;
import org.postgresql.core.*;

/**
 * This class implements the java.sql.ResultSet interface for JDBC2.
 * However most of the implementation is really done in
 * org.postgresql.jdbc2.AbstractJdbc2ResultSet
 */
public class Jdbc2ResultSet extends org.postgresql.jdbc2.AbstractJdbc2ResultSet implements java.sql.ResultSet
{
    Jdbc2ResultSet(Query originalQuery, BaseStatement statement, Field[] fields, Vector tuples, ResultCursor cursor,
                   int maxRows, int maxFieldSize, int rsType, int rsConcurrency) throws SQLException
    {
        super(originalQuery, statement, fields, tuples, cursor, maxRows, maxFieldSize, rsType, rsConcurrency);
    }

    public ResultSetMetaData getMetaData() throws SQLException
    {
        checkClosed();
        return new Jdbc2ResultSetMetaData(connection, fields);
    }

    public java.sql.Clob getClob(int i) throws SQLException
    {
        checkResultSet(i);
        if (wasNullFlag)
            return null;

        return new org.postgresql.jdbc2.Jdbc2Clob(connection, getLong(i));
    }

    public java.sql.Blob getBlob(int i) throws SQLException
    {
        checkResultSet(i);
        if (wasNullFlag)
            return null;

        return new org.postgresql.jdbc2.Jdbc2Blob(connection, getLong(i));
    }

    public java.sql.Array createArray(int i) throws SQLException
    {
        checkResultSet(i);
        int oid = fields[i - 1].getOID();
        String value = getFixedString(i);
        return new Jdbc2Array(connection, oid, value);
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

