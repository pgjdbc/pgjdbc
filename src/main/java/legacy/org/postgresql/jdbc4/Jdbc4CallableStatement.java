/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc4;

import java.sql.*;
import java.util.Map;

class Jdbc4CallableStatement extends Jdbc4PreparedStatement implements CallableStatement
{
    Jdbc4CallableStatement(Jdbc4Connection connection, String sql, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(connection, sql, true, rsType, rsConcurrency, rsHoldability);
        if ( !connection.haveMinimumServerVersion("8.1") || connection.getProtocolVersion() == 2)
        {
            // if there is no out parameter before the function determined by modifyJdbcCall then do not
            // set adjustIndex to true
            adjustIndex = outParmBeforeFunc;
        }
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
