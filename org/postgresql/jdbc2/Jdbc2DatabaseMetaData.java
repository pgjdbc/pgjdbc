/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;


public class Jdbc2DatabaseMetaData extends org.postgresql.jdbc2.AbstractJdbc2DatabaseMetaData implements java.sql.DatabaseMetaData
{
    public Jdbc2DatabaseMetaData(Jdbc2Connection conn)
    {
        super(conn);
    }

}
