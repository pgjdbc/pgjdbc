/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3/Jdbc3CallableStatement.java,v 1.12 2005/07/08 17:38:30 davec Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3;

import java.sql.*;
import java.util.Map;

class Jdbc3CallableStatement extends Jdbc3PreparedStatement implements CallableStatement
{
    
    Jdbc3CallableStatement(Jdbc3Connection connection, String sql, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(connection, sql, true, rsType, rsConcurrency, rsHoldability);
        if ( !connection.haveMinimumServerVersion("8.1") || connection.getProtocolVersion() == 2)
        {
            adjustIndex = true;
        }
    }
   
    public Object getObject(int i, Map map) throws SQLException
    {
        return getObjectImpl(i, map);
    }

    public Object getObject(String s, Map map) throws SQLException
    {
        return getObjectImpl(s, map);
    }
    
}
