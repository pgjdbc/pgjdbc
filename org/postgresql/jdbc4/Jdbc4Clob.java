/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc4/Jdbc4Clob.java,v 1.2 2007/02/19 06:00:32 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;


public class Jdbc4Clob extends AbstractJdbc4Clob implements java.sql.Clob
{

    public Jdbc4Clob(org.postgresql.core.BaseConnection conn, long oid) throws java.sql.SQLException
    {
        super(conn, oid);
    }

}
