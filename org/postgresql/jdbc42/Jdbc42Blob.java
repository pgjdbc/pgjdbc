/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc42;

import java.sql.SQLException;

import org.postgresql.jdbc4.AbstractJdbc4Blob;

public class Jdbc42Blob extends AbstractJdbc4Blob implements java.sql.Blob
{

    public Jdbc42Blob(org.postgresql.core.BaseConnection conn, long oid) throws SQLException
    {
        super(conn, oid);
    }

}
