/*-------------------------------------------------------------------------
*
* Copyright (c) 2005-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc4;

import java.sql.SQLException;

import legacy.org.postgresql.Driver;
import legacy.org.postgresql.core.BaseConnection;
import legacy.org.postgresql.jdbc3.AbstractJdbc3ParameterMetaData;

public abstract class AbstractJdbc4ParameterMetaData extends AbstractJdbc3ParameterMetaData
{

    public AbstractJdbc4ParameterMetaData(BaseConnection connection, int oids[])
    {
        super(connection, oids);
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

