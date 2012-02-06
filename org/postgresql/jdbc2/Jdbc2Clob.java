/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;


public class Jdbc2Clob extends AbstractJdbc2Clob implements java.sql.Clob
{

    public Jdbc2Clob(org.postgresql.core.BaseConnection conn, long oid) throws java.sql.SQLException
    {
        super(conn, oid);
    }

}
