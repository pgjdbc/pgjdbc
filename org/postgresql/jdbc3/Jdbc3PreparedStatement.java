/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3/Jdbc3PreparedStatement.java,v 1.9 2004/11/09 08:50:36 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3;

import java.sql.*;

class Jdbc3PreparedStatement extends Jdbc3Statement implements PreparedStatement
{
    Jdbc3PreparedStatement(Jdbc3Connection connection, String sql, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        this(connection, sql, false, rsType, rsConcurrency, rsHoldability);
    }

    protected Jdbc3PreparedStatement(Jdbc3Connection connection, String sql, boolean isCallable, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(connection, sql, isCallable, rsType, rsConcurrency, rsHoldability);
    }
}
