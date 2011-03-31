package org.postgresql.ds.jdbc4;

import java.sql.SQLFeatureNotSupportedException;

import org.postgresql.ds.jdbc23.AbstractJdbc23ConnectionPoolDataSource;

public class AbstractJdbc4ConnectionPoolDataSource
	extends AbstractJdbc23ConnectionPoolDataSource
{

    public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getParentLogger()");
    }

}

