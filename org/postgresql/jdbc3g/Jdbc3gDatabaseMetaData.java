/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
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
