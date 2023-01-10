/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc4;


import legacy.org.postgresql.core.BaseStatement;
import legacy.org.postgresql.core.Field;
import legacy.org.postgresql.core.Query;
import legacy.org.postgresql.core.ResultCursor;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Map;
import java.util.Vector;

/**
 * This class implements the java.sql.ResultSet interface for JDBC4.
 * However most of the implementation is really done in
 * org.postgresql.jdbc4.AbstractJdbc4ResultSet or one of it's parents
 */
public class Jdbc4ResultSet extends AbstractJdbc4ResultSet implements java.sql.ResultSet
{
    Jdbc4ResultSet(Query originalQuery, BaseStatement statement, Field[] fields, Vector tuples, ResultCursor cursor,
                   int maxRows, int maxFieldSize, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(originalQuery, statement, fields, tuples, cursor, maxRows, maxFieldSize, rsType, rsConcurrency, rsHoldability);
    }

    protected java.sql.ResultSetMetaData createMetaData() throws SQLException
    {
        return new Jdbc4ResultSetMetaData(connection, fields);
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

