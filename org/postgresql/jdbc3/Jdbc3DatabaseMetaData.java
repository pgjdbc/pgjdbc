/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3/Jdbc3DatabaseMetaData.java,v 1.4 2004/11/09 08:50:32 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3;


public class Jdbc3DatabaseMetaData extends org.postgresql.jdbc3.AbstractJdbc3DatabaseMetaData implements java.sql.DatabaseMetaData
{

    public Jdbc3DatabaseMetaData(Jdbc3Connection conn)
    {
        super(conn);
    }

}
