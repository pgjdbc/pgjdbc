/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3/Jdbc3Blob.java,v 1.5 2007/02/19 06:00:25 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3;


import java.sql.*;

public class Jdbc3Blob extends org.postgresql.jdbc3.AbstractJdbc3Blob implements java.sql.Blob
{

    public Jdbc3Blob(org.postgresql.core.BaseConnection conn, long oid) throws SQLException
    {
        super(conn, oid);
    }

}
