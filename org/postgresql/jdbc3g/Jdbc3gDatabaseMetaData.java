/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3g/Jdbc3gDatabaseMetaData.java,v 1.3 2004/11/09 08:51:22 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3g;


public class Jdbc3gDatabaseMetaData extends org.postgresql.jdbc3.AbstractJdbc3DatabaseMetaData implements java.sql.DatabaseMetaData
{

    public Jdbc3gDatabaseMetaData(Jdbc3gConnection conn)
    {
        super(conn);
    }

}
