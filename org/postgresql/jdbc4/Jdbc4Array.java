/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.util.HashMap;
import java.util.Map;
import org.postgresql.core.*;
import org.postgresql.util.ByteConverter;

import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.UUID;

public class Jdbc4Array extends org.postgresql.jdbc2.AbstractJdbc2Array implements java.sql.Array
{
    static {
        if (otherArrElementBuilders == null) otherArrElementBuilders = new HashMap();
        otherArrElementBuilders.put(new Integer(Oid.UUID), new UUIDArrayElementBuilder());
        otherArrElementBuilders.put(new Integer(Oid.UUID_ARRAY), new UUIDArrayElementBuilder());
    }

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

    public void free() throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "free()");
    }

    /////////////////////////////////////////////////////////////////////////////////

    static class UUIDArrayElementBuilder implements ArrayElementBuilder {

        @Override
        public Class getElementClass() {
            return UUID.class;
        }

        @Override
        public Object buildElement(byte[] bytes, int pos, int len) {
            return new UUID(ByteConverter.int8(bytes, pos + 0), ByteConverter.int8(bytes, pos + 8));
        }

        @Override
        public Object buildElement(String literal) {
            return UUID.fromString(literal);
        }
    }
}
