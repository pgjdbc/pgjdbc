/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc4;


import legacy.org.postgresql.core.BaseConnection;

import java.sql.*;

public class Jdbc4Blob extends AbstractJdbc4Blob implements java.sql.Blob
{

    public Jdbc4Blob(BaseConnection conn, long oid) throws SQLException
    {
        super(conn, oid);
    }

}
