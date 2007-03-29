/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc4/Jdbc4Blob.java,v 1.2 2007/02/19 06:00:32 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;


import java.sql.*;

public class Jdbc4Blob extends AbstractJdbc4Blob implements java.sql.Blob
{

    public Jdbc4Blob(org.postgresql.core.BaseConnection conn, long oid) throws SQLException
    {
        super(conn, oid);
    }

}
