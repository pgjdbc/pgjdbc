/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.ds.jdbc4;

import legacy.org.postgresql.Driver;
import legacy.org.postgresql.ds.jdbc23.AbstractJdbc23SimpleDataSource;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

public abstract class AbstractJdbc4SimpleDataSource extends AbstractJdbc23SimpleDataSource
{
    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "isWrapperFor(Class<?>)");
    }

    public <T> T unwrap(Class<T> iface) throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "unwrap(Class<T>)");
    }

    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException
    {
        throw Driver.notImplemented(this.getClass(), "getParentLogger()");
    }

}
