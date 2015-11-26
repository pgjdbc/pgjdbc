/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc42;

import java.sql.*;

class Jdbc42PreparedStatement extends Jdbc42Statement implements PreparedStatement
{
    Jdbc42PreparedStatement(Jdbc42Connection connection, String sql, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        this(connection, sql, false, rsType, rsConcurrency, rsHoldability);
    }

    Jdbc42PreparedStatement(Jdbc42Connection connection, String sql, boolean isCallable, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(connection, sql, isCallable, rsType, rsConcurrency, rsHoldability);
    }
}
