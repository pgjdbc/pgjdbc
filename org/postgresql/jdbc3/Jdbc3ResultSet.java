/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3/Jdbc3ResultSet.java,v 1.16 2007/12/01 12:50:44 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3;


import java.sql.*;
import java.util.Map;
import java.util.Vector;
import org.postgresql.core.*;

/**
 * This class implements the java.sql.ResultSet interface for JDBC3.
 * However most of the implementation is really done in
 * org.postgresql.jdbc3.AbstractJdbc3ResultSet or one of it's parents
 */
public class Jdbc3ResultSet extends org.postgresql.jdbc3.AbstractJdbc3ResultSet implements java.sql.ResultSet
{
    Jdbc3ResultSet(Query originalQuery, BaseStatement statement, Field[] fields, Vector tuples, ResultCursor cursor,
                   int maxRows, int maxFieldSize, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(originalQuery, statement, fields, tuples, cursor, maxRows, maxFieldSize, rsType, rsConcurrency, rsHoldability);
    }

    public java.sql.ResultSetMetaData getMetaData() throws SQLException
    {
        checkClosed();
        return new Jdbc3ResultSetMetaData(connection, fields);
    }

    public java.sql.Clob getClob(int i) throws SQLException
    {
        checkResultSet(i);
        if (wasNullFlag)
            return null;

        return new Jdbc3Clob(connection, getLong(i));
    }

    public java.sql.Blob getBlob(int i) throws SQLException
    {
        checkResultSet(i);
        if (wasNullFlag)
            return null;

        return new Jdbc3Blob(connection, getLong(i));
    }

    public Array createArray(int i) throws SQLException
    {
        checkResultSet(i);
        int oid = fields[i - 1].getOID();
        String value = getFixedString(i);
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

