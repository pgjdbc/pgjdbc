/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3g/Jdbc3gResultSet.java,v 1.4 2004/12/17 21:24:30 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3g;


import java.sql.*;
import java.util.Map;
import java.util.Vector;
import org.postgresql.core.*;

/**
 * This class implements the java.sql.ResultSet interface for JDBC3.
 * However most of the implementation is really done in
 * org.postgresql.jdbc3.AbstractJdbc3ResultSet or one of it's parents
 */
public class Jdbc3gResultSet extends org.postgresql.jdbc3.AbstractJdbc3ResultSet implements java.sql.ResultSet
{
    Jdbc3gResultSet(Query originalQuery, BaseStatement statement, Field[] fields, Vector tuples, ResultCursor cursor,
                    int maxRows, int maxFieldSize, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(originalQuery, statement, fields, tuples, cursor, maxRows, maxFieldSize, rsType, rsConcurrency, rsHoldability);
    }

    public java.sql.ResultSetMetaData getMetaData() throws SQLException
    {
        checkClosed();
        return new Jdbc3gResultSetMetaData(connection, fields);
    }

    public java.sql.Clob getClob(int i) throws SQLException
    {
        checkResultSet(i);
        wasNullFlag = (this_row[i - 1] == null);
        if (wasNullFlag)
            return null;

        return new Jdbc3gClob(connection, getInt(i));
    }

    public java.sql.Blob getBlob(int i) throws SQLException
    {
        checkResultSet(i);
        wasNullFlag = (this_row[i - 1] == null);
        if (wasNullFlag)
            return null;

        return new Jdbc3gBlob(connection, getInt(i));
    }

    public Array createArray(int i) throws SQLException
    {
        checkResultSet(i);
        return new Jdbc3gArray(connection, i, fields[i - 1], this);
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

