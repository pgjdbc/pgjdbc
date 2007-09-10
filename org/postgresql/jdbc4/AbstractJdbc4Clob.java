/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc4/AbstractJdbc4Clob.java,v 1.4 2007/03/29 06:13:54 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.io.Reader;
import java.sql.SQLException;

public abstract class AbstractJdbc4Clob extends org.postgresql.jdbc3.AbstractJdbc3Clob
{

    public AbstractJdbc4Clob(org.postgresql.core.BaseConnection conn, long oid) throws SQLException
    {
        super(conn, oid);
    }

    public synchronized Reader getCharacterStream(long pos, long length) throws SQLException
    {
        checkFreed();
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getCharacterStream(long, long)");
    }

}
