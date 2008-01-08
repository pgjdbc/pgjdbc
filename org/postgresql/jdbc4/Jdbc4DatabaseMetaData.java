/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc4/Jdbc4DatabaseMetaData.java,v 1.1 2006/06/08 10:34:52 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;


public class Jdbc4DatabaseMetaData extends AbstractJdbc4DatabaseMetaData implements java.sql.DatabaseMetaData
{

    public Jdbc4DatabaseMetaData(Jdbc4Connection conn)
    {
        super(conn);
    }

}
