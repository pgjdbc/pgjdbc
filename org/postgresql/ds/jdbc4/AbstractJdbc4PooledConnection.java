/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.ds.jdbc4;

import org.postgresql.ds.jdbc23.AbstractJdbc23PooledConnection;

import javax.sql.StatementEventListener;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;

public abstract class AbstractJdbc4PooledConnection extends AbstractJdbc23PooledConnection {

    public AbstractJdbc4PooledConnection(Connection con, boolean autoCommit, boolean isXA) {
        super(con, autoCommit, isXA);
    }

    public void removeStatementEventListener(StatementEventListener listener) {
    }

    public void addStatementEventListener(StatementEventListener listener) {
    }

    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getParentLogger()");
    }

}
