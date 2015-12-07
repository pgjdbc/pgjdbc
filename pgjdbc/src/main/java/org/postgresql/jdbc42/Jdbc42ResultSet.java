/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc42;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.postgresql.core.BaseStatement;
import org.postgresql.core.Field;
import org.postgresql.core.Query;
import org.postgresql.core.ResultCursor;

public class Jdbc42ResultSet extends AbstractJdbc42ResultSet implements ResultSet
{

    Jdbc42ResultSet(Query originalQuery, BaseStatement statement, Field[] fields, List tuples, ResultCursor cursor, int maxRows, int maxFieldSize, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(originalQuery, statement, fields, tuples, cursor, maxRows, maxFieldSize, rsType, rsConcurrency, rsHoldability);
    }

    protected java.sql.ResultSetMetaData createMetaData() throws SQLException
    {
        return new Jdbc42ResultSetMetaData(connection, fields);
    }

    protected java.sql.Clob makeClob(long oid) throws SQLException
    {
        return new Jdbc42Clob(connection, oid);
    }

    protected java.sql.Blob makeBlob(long oid) throws SQLException
    {
        return new Jdbc42Blob(connection, oid);
    }

    protected Array makeArray(int oid, byte[] value) throws SQLException
    {
        return new Jdbc42Array(connection, oid, value);
    }

    protected Array makeArray(int oid, String value) throws SQLException
    {
        return new Jdbc42Array(connection, oid, value);
    }

    public Object getObject(String s, Map <String, Class <?>> map) throws SQLException
    {
        return getObjectImpl(s, map);
    }

    public Object getObject(int i, Map <String, Class <?>> map) throws SQLException
    {
        return getObjectImpl(i, map);
    }

}
