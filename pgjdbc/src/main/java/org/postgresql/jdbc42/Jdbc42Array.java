/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc42;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc4.AbstractJdbc4Array;

public class Jdbc42Array extends AbstractJdbc4Array implements java.sql.Array
{
    public Jdbc42Array(BaseConnection conn, int oid, String fieldString) throws SQLException
    {
        super(conn, oid, fieldString);
    }

    public Jdbc42Array(BaseConnection conn, int oid, byte[] fieldBytes) throws SQLException
    {
        super(conn, oid, fieldBytes);
    }

    public Object getArray(Map < String, Class < ? >> map) throws SQLException
    {
        return getArrayImpl(map);
    }

    public Object getArray(long index, int count, Map < String, Class < ? >> map) throws SQLException
    {
        return getArrayImpl(index, count, map);
    }

    public ResultSet getResultSet(Map < String, Class < ? >> map) throws SQLException
    {
        return getResultSetImpl(map);
    }

    public ResultSet getResultSet(long index, int count, Map < String, Class < ? >> map) throws SQLException
    {
        return getResultSetImpl(index, count, map);
    }

}
