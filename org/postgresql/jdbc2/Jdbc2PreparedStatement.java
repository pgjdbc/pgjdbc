/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/Jdbc2PreparedStatement.java,v 1.9 2004/11/09 08:49:18 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import java.sql.*;

class Jdbc2PreparedStatement extends Jdbc2Statement implements PreparedStatement
{
    Jdbc2PreparedStatement(Jdbc2Connection connection, String sql, int rsType, int rsConcurrency) throws SQLException
    {
        this(connection, sql, false, rsType, rsConcurrency);
    }

    protected Jdbc2PreparedStatement(Jdbc2Connection connection, String sql, boolean isCallable, int rsType, int rsConcurrency) throws SQLException
    {
        super(connection, sql, isCallable, rsType, rsConcurrency);
    }
}

