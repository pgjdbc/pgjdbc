/*-------------------------------------------------------------------------
*
* Copyright (c) 2004, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3/Jdbc3Blob.java,v 1.2 2004/11/07 22:16:26 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3;


import java.sql.*;

public class Jdbc3Blob extends org.postgresql.jdbc3.AbstractJdbc3Blob implements java.sql.Blob
{

    public Jdbc3Blob(org.postgresql.PGConnection conn, int oid) throws SQLException
    {
        super(conn, oid);
    }

}
