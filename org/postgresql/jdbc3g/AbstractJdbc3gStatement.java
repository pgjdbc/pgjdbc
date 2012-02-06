/*-------------------------------------------------------------------------
*
* Copyright (c) 2008-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.jdbc3g;

import java.sql.*;
import java.util.UUID;

import org.postgresql.core.Oid;
import org.postgresql.jdbc3.AbstractJdbc3Connection;

public abstract class AbstractJdbc3gStatement extends org.postgresql.jdbc3.AbstractJdbc3Statement
{
    public AbstractJdbc3gStatement (AbstractJdbc3Connection c, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(c, rsType, rsConcurrency, rsHoldability);
    }

    public AbstractJdbc3gStatement(AbstractJdbc3Connection connection, String sql, boolean isCallable, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(connection, sql, isCallable, rsType, rsConcurrency, rsHoldability);
    }

    public void setObject(int parameterIndex, Object x) throws SQLException
    {
        if (x instanceof UUID && connection.haveMinimumServerVersion("8.3"))
        {
            setString(parameterIndex, x.toString(), Oid.UUID);
        } else {
            super.setObject(parameterIndex, x);
        }
    }

    public void setObject(int parameterIndex, Object x, int targetSqlType, int scale) throws SQLException
    {
        if (targetSqlType == Types.OTHER && x instanceof UUID && connection.haveMinimumServerVersion("8.3"))
        {
            setString(parameterIndex, x.toString(), Oid.UUID);
        } else {
            super.setObject(parameterIndex, x, targetSqlType, scale);
        }
    }
}

