/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/ds/jdbc4/AbstractJdbc4SimpleDataSource.java,v 1.1 2006/11/29 04:00:27 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.ds.jdbc4;

import java.sql.SQLException;

import org.postgresql.ds.jdbc23.AbstractJdbc23SimpleDataSource;

public abstract class AbstractJdbc4SimpleDataSource extends AbstractJdbc23SimpleDataSource
{
    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "isWrapperFor(Class<?>)");
    }

    public <T> T unwrap(Class<T> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "unwrap(Class<T>)");
    }

}
