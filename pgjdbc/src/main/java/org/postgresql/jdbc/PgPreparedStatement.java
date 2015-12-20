/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;

class PgPreparedStatement extends PgStatement implements PreparedStatement
{
    PgPreparedStatement(PgConnection connection, String sql, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        this(connection, sql, false, rsType, rsConcurrency, rsHoldability);
    }

    PgPreparedStatement(PgConnection connection, String sql, boolean isCallable, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(connection, sql, isCallable, rsType, rsConcurrency, rsHoldability);
    }
}
