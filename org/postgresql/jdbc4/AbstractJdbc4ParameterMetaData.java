/*-------------------------------------------------------------------------
*
* Copyright (c) 2005-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.sql.SQLException;
import org.postgresql.core.BaseConnection;

public abstract class AbstractJdbc4ParameterMetaData extends org.postgresql.jdbc3.AbstractJdbc3ParameterMetaData
{

    public AbstractJdbc4ParameterMetaData(BaseConnection connection, int oids[])
    {
        super(connection, oids);
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "isWrapperFor(Class<?>)");
    }

    public <T> T unwrap(Class<T> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "unwrap(Class<T>)");
    }

}

