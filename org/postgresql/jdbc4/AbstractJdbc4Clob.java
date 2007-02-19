/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc4/AbstractJdbc4Clob.java,v 1.1 2006/06/08 10:34:51 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.io.Reader;
import java.sql.SQLException;

public class AbstractJdbc4Clob extends org.postgresql.jdbc3.AbstractJdbc3Clob
{

    public AbstractJdbc4Clob(org.postgresql.PGConnection conn, long oid) throws SQLException
    {
        super(conn, oid);
    }

    public Reader getCharacterStream(long pos, long length) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getCharacterStream(long, long)");
    }

    public void free() throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "free()");
    }

}
