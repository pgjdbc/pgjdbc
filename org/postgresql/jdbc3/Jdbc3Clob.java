/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3/Jdbc3Clob.java,v 1.5 2007/02/19 06:00:25 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3;


public class Jdbc3Clob extends org.postgresql.jdbc3.AbstractJdbc3Clob implements java.sql.Clob
{

    public Jdbc3Clob(org.postgresql.core.BaseConnection conn, long oid) throws java.sql.SQLException
    {
        super(conn, oid);
    }

}
