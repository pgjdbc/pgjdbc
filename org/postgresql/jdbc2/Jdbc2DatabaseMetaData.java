/*-------------------------------------------------------------------------
*
* Copyright (c) 2004, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/Jdbc2DatabaseMetaData.java,v 1.3 2004/11/07 22:16:17 jurka Exp $
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
