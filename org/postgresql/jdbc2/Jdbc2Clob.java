/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/Jdbc2Clob.java,v 1.3 2004/11/09 08:49:10 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;


public class Jdbc2Clob extends AbstractJdbc2Clob implements java.sql.Clob
{

    public Jdbc2Clob(org.postgresql.PGConnection conn, int oid) throws java.sql.SQLException
    {
        super(conn, oid);
    }

}
