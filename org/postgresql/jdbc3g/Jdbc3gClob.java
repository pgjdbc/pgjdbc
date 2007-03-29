/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3g/Jdbc3gClob.java,v 1.5 2007/02/19 06:00:25 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3g;


public class Jdbc3gClob extends org.postgresql.jdbc3.AbstractJdbc3Clob implements java.sql.Clob
{

    public Jdbc3gClob(org.postgresql.core.BaseConnection conn, long oid) throws java.sql.SQLException
    {
        super(conn, oid);
    }

}
