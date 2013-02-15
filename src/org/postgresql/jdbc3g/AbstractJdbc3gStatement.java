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
import org.postgresql.util.ByteConverter;

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
            setUuid(parameterIndex, (UUID)x);
        } else {
            super.setObject(parameterIndex, x);
        }
    }

    public void setObject(int parameterIndex, Object x, int targetSqlType, int scale) throws SQLException
    {
        if (targetSqlType == Types.OTHER && x instanceof UUID && connection.haveMinimumServerVersion("8.3"))
        {
            setUuid(parameterIndex, (UUID) x);
        } else {
            super.setObject(parameterIndex, x, targetSqlType, scale);
        }
    }

    private void setUuid(int parameterIndex, UUID uuid) throws SQLException {
        if (connection.binaryTransferSend(Oid.UUID)) {
            byte[] val = new byte[16];
            ByteConverter.int8(val, 0, uuid.getMostSignificantBits());
            ByteConverter.int8(val, 8, uuid.getLeastSignificantBits());
            bindBytes(parameterIndex, val, Oid.UUID);
        } else {
            bindLiteral(parameterIndex, uuid.toString(), Oid.UUID);
        }
    }
}