/*-------------------------------------------------------------------------
*
* Copyright (c) 2008-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc3g;

import legacy.org.postgresql.core.Oid;
import legacy.org.postgresql.core.TypeInfo;
import legacy.org.postgresql.jdbc3.AbstractJdbc3Connection;

import java.sql.SQLException;
import java.util.Properties;

public abstract class AbstractJdbc3gConnection extends AbstractJdbc3Connection
{

    public AbstractJdbc3gConnection(String host, int port, String user, String database, Properties info, String url) throws SQLException {
        super(host, port, user, database, info, url);

        TypeInfo types = getTypeInfo();
        if (haveMinimumServerVersion("8.3")) {
            types.addCoreType("uuid", Oid.UUID, java.sql.Types.OTHER, "java.util.UUID", Oid.UUID_ARRAY);
        }
    }

}

