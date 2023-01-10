/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc4;

import legacy.org.postgresql.Driver;
import legacy.org.postgresql.core.BaseConnection;
import legacy.org.postgresql.jdbc3.AbstractJdbc3Clob;

import java.io.Reader;
import java.sql.SQLException;

public abstract class AbstractJdbc4Clob extends AbstractJdbc3Clob
{

    public AbstractJdbc4Clob(BaseConnection conn, long oid) throws SQLException
    {
        super(conn, oid);
    }

    public synchronized Reader getCharacterStream(long pos, long length) throws SQLException
    {
        checkFreed();
        throw Driver.notImplemented(this.getClass(), "getCharacterStream(long, long)");
    }

}
