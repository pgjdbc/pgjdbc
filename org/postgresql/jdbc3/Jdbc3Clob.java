/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3/Jdbc3Clob.java,v 1.3 2004/11/09 08:50:21 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3;


public class Jdbc3Clob extends org.postgresql.jdbc3.AbstractJdbc3Clob implements java.sql.Clob
{

    public Jdbc3Clob(org.postgresql.PGConnection conn, int oid) throws java.sql.SQLException
    {
        super(conn, oid);
    }

}
