/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.ds.jdbc4;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

import org.postgresql.ds.jdbc23.AbstractJdbc23SimpleDataSource;

public abstract class AbstractJdbc4SimpleDataSource extends AbstractJdbc23SimpleDataSource
{
    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        return iface.isAssignableFrom(getClass());
    }

    public <T> T unwrap(Class<T> iface) throws SQLException
    {
        if (iface.isAssignableFrom(getClass()))
        {
            return (T) this;
        }
        throw new SQLException("Cannot unwrap to " + iface.getName());
    }

    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getParentLogger()");
    }

}
