/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3g/Jdbc3gCallableStatement.java,v 1.3 2004/11/09 08:51:22 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3g;

import java.sql.*;
import java.util.Map;

class Jdbc3gCallableStatement extends Jdbc3gPreparedStatement implements CallableStatement
{
    Jdbc3gCallableStatement(Jdbc3gConnection connection, String sql, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(connection, sql, true, rsType, rsConcurrency, rsHoldability);
    }

    public Object getObject(int i, Map < String, Class < ? >> map) throws SQLException
    {
        return getObjectImpl(i, map);
    }

    public Object getObject(String s, Map < String, Class < ? >> map) throws SQLException
    {
        return getObjectImpl(s, map);
    }

}
