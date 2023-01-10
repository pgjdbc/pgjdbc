/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc2;

import java.sql.SQLException;

import legacy.org.postgresql.core.BaseConnection;

public abstract class AbstractJdbc2Blob extends AbstractJdbc2BlobClob
{

    public AbstractJdbc2Blob(BaseConnection conn, long oid) throws SQLException
    {
        super(conn, oid);
    }

}
