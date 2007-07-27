/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/Jdbc2CallableStatement.java,v 1.13 2006/01/30 20:09:05 jurka Exp $
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
        if ( !connection.haveMinimumServerVersion("8.1") || connection.getProtocolVersion() == 2)
        {
            // if there is no out parameter before the function determined by modifyJdbcCall then do not
            // set adjustIndex to true
            adjustIndex = outParmBeforeFunc;
        }
    }
    public void registerOutParameter( int parameterIndex, int sqlType ) throws SQLException
    {
        registerOutParameter(parameterIndex, sqlType, !adjustIndex );
    }
    
    public void registerOutParameter( int parameterIndex, int sqlType, int scale ) throws SQLException
    {
        registerOutParameter(parameterIndex, sqlType );
    }
    public Object getObject(int i, Map map) throws SQLException
    {
        return getObjectImpl(i, map);
    }
}

