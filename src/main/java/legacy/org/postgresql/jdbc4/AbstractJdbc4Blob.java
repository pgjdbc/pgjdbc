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
import legacy.org.postgresql.jdbc3.AbstractJdbc3Blob;

import java.sql.*;

public abstract class AbstractJdbc4Blob extends AbstractJdbc3Blob
{

    public AbstractJdbc4Blob(BaseConnection conn, long oid) throws SQLException
    {
        super(conn, oid);
    }

    public synchronized java.io.InputStream getBinaryStream(long pos, long length) throws SQLException
    {
        checkFreed();
        throw Driver.notImplemented(this.getClass(), "getBinaryStream(long, long)");
    }

}

