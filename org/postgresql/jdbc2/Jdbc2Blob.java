/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;


public class Jdbc2Blob extends AbstractJdbc2Blob implements java.sql.Blob
{

    public Jdbc2Blob(org.postgresql.core.BaseConnection conn, long oid) throws java.sql.SQLException
    {
        super(conn, oid);
    }

}
