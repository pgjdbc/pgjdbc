/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc4;


import legacy.org.postgresql.core.BaseConnection;

public class Jdbc4Clob extends AbstractJdbc4Clob implements java.sql.Clob
{

    public Jdbc4Clob(BaseConnection conn, long oid) throws java.sql.SQLException
    {
        super(conn, oid);
    }

}
