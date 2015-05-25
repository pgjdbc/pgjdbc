/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc42;

import java.sql.SQLException;
import java.sql.SQLType;
import java.util.List;

import org.postgresql.Driver;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.Field;
import org.postgresql.core.Query;
import org.postgresql.core.ResultCursor;
import org.postgresql.jdbc4.AbstractJdbc4ResultSet;

public abstract class AbstractJdbc42ResultSet extends AbstractJdbc4ResultSet
{

    protected AbstractJdbc42ResultSet(Query originalQuery, BaseStatement statement, Field[] fields, List tuples, ResultCursor cursor, int maxRows, int maxFieldSize, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(originalQuery, statement, fields, tuples, cursor, maxRows, maxFieldSize, rsType, rsConcurrency, rsHoldability);
    }

    public void updateObject(int columnIndex, Object x, SQLType targetSqlType, int scaleOrLength)  throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "updateObject");
    }

    public void updateObject(String columnLabel, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "updateObject");
    }

    public void updateObject(int columnIndex, Object x, SQLType targetSqlType) throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "updateObject");
    }

    public void updateObject(String columnLabel, Object x, SQLType targetSqlType) throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "updateObject");
    }
}
