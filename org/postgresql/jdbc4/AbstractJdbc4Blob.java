/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc4/AbstractJdbc4Blob.java,v 1.1 2006/06/08 10:34:51 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;


import java.sql.*;

public class AbstractJdbc4Blob extends org.postgresql.jdbc3.AbstractJdbc3Blob
{

    public AbstractJdbc4Blob(org.postgresql.PGConnection conn, long oid) throws SQLException
    {
        super(conn, oid);
    }

    public java.io.InputStream getBinaryStream(long pos, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getBinaryStream(long, long)");
    }

    public void free() throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "free()");
    }

}

