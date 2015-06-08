/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc42;

import org.postgresql.jdbc4.AbstractJdbc4Clob;

public class Jdbc42Clob extends AbstractJdbc4Clob implements java.sql.Clob
{

    public Jdbc42Clob(org.postgresql.core.BaseConnection conn, long oid) throws java.sql.SQLException
    {
        super(conn, oid);
    }

}
