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
import legacy.org.postgresql.core.Field;
import legacy.org.postgresql.jdbc2.AbstractJdbc2ResultSetMetaData;

import java.sql.SQLException;

abstract class AbstractJdbc4ResultSetMetaData extends AbstractJdbc2ResultSetMetaData
{

    public AbstractJdbc4ResultSetMetaData(BaseConnection connection, Field[] fields)
    {
        super(connection, fields);
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "isWrapperFor(Class<?>)");
    }

    public <T> T unwrap(Class<T> iface) throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "unwrap(Class<T>)");
    }

}


