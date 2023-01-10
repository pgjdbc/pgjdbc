/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc4;

import legacy.org.postgresql.Driver;
import legacy.org.postgresql.core.BaseConnection;
import legacy.org.postgresql.jdbc2.AbstractJdbc2Array;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

public class Jdbc4Array extends AbstractJdbc2Array implements java.sql.Array
{
    public Jdbc4Array(BaseConnection conn, int oid, String fieldString) throws SQLException
    {
        super(conn, oid, fieldString);
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

    public void free() throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "free()");
    }

}
