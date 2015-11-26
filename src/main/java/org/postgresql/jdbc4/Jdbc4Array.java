/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.util.Map;
import org.postgresql.core.*;

import java.sql.SQLException;
import java.sql.ResultSet;

public class Jdbc4Array extends AbstractJdbc4Array implements java.sql.Array
{

    public Jdbc4Array(BaseConnection conn, int oid, String fieldString) throws SQLException
    {
        super(conn, oid, fieldString);
    }

    public Jdbc4Array(BaseConnection conn, int oid, byte[] fieldBytes) throws SQLException
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
