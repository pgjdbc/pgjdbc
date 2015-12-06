package org.postgresql.ds.jdbc4;

import org.postgresql.ds.jdbc23.AbstractJdbc23ConnectionPoolDataSource;

import java.sql.SQLFeatureNotSupportedException;

public class AbstractJdbc4ConnectionPoolDataSource
        extends AbstractJdbc23ConnectionPoolDataSource {

    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getParentLogger()");
    }

}

