/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3/Jdbc3CallableStatement.java,v 1.11 2005/01/11 08:25:46 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.*;
import java.util.Calendar;
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
    public void registerOutParameter( int parameterIndex, int sqlType ) throws SQLException
    {
        // if this isn't 8.1 or we are using protocol version 2 then we don't
        // register the parameter
        switch( sqlType )
        {
        		case Types.BOOLEAN:
        		    	sqlType = Types.BIT;
        			break;
    			default:
        		
        }
        super.registerOutParameter(parameterIndex, sqlType, !adjustIndex );
    }
    public void registerOutParameter(int parameterIndex, int sqlType,
            int scale) throws SQLException
    {
        // ignore scale for now
        registerOutParameter(parameterIndex, sqlType );
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
