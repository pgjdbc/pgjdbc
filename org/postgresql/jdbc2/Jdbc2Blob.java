/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/Jdbc2Blob.java,v 1.3 2004/11/09 08:49:03 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;


public class Jdbc2Blob extends AbstractJdbc2Blob implements java.sql.Blob
{

    public Jdbc2Blob(org.postgresql.PGConnection conn, int oid) throws java.sql.SQLException
    {
        super(conn, oid);
    }

}
