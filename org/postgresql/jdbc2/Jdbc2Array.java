/*-------------------------------------------------------------------------
*
* Copyright (c) 2004, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/Jdbc2Array.java,v 1.2 2004/11/07 22:16:10 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import java.util.Map;
import org.postgresql.core.*;
import java.sql.SQLException;
import java.sql.ResultSet;

public class Jdbc2Array extends AbstractJdbc2Array implements java.sql.Array
{
    public Jdbc2Array(BaseConnection conn, int idx, Field field, BaseResultSet rs) throws SQLException
    {
        super(conn, idx, field, rs);
    }

    public Object getArray(Map map) throws SQLException
    {
        return getArrayImpl(map);
    }

    public Object getArray(long index, int count, Map map) throws SQLException
    {
        return getArrayImpl(index, count, map);
    }

    public ResultSet getResultSet(Map map) throws SQLException
    {
        return getResultSetImpl(map);
    }

    public ResultSet getResultSet(long index, int count, Map map) throws SQLException
    {
        return getResultSetImpl(index, count, map);
    }

}
