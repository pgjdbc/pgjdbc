/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/ds/jdbc4/AbstractJdbc4PoolingDataSource.java,v 1.3 2011/03/31 06:25:42 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.ds.jdbc4;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

import org.postgresql.ds.jdbc23.AbstractJdbc23PoolingDataSource;

public abstract class AbstractJdbc4PoolingDataSource extends AbstractJdbc23PoolingDataSource 
{

    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "isWrapperFor(Class<?>)");
    }

    public <T> T unwrap(Class<T> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "unwrap(Class<T>)");
    }

    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getParentLogger()");
    }

}
