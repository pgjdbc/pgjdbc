/*-------------------------------------------------------------------------
*
* Copyright (c) 2004, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/Jdbc2CallableStatement.java,v 1.9 2004/11/07 22:16:13 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import java.sql.*;
import java.util.Map;

class Jdbc2CallableStatement extends Jdbc2PreparedStatement implements CallableStatement
{
    Jdbc2CallableStatement(Jdbc2Connection connection, String sql, int rsType, int rsConcurrency) throws SQLException
    {
        super(connection, sql, true, rsType, rsConcurrency);
    }

    public Object getObject(int i, Map map) throws SQLException
    {
        return getObjectImpl(i, map);
    }
}

