/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3g/Jdbc3gPreparedStatement.java,v 1.3 2004/11/09 08:51:27 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3g;

import java.sql.*;

class Jdbc3gPreparedStatement extends Jdbc3gStatement implements PreparedStatement
{
    Jdbc3gPreparedStatement(Jdbc3gConnection connection, String sql, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        this(connection, sql, false, rsType, rsConcurrency, rsHoldability);
    }

    protected Jdbc3gPreparedStatement(Jdbc3gConnection connection, String sql, boolean isCallable, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(connection, sql, isCallable, rsType, rsConcurrency, rsHoldability);
    }
}
