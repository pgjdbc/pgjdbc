/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/Jdbc2DatabaseMetaData.java,v 1.4 2004/11/09 08:49:15 jurka Exp $
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
