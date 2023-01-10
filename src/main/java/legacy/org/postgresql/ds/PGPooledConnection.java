/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2004-2011, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */
package legacy.org.postgresql.ds;

import legacy.org.postgresql.ds.jdbc4.AbstractJdbc4PooledConnection;

import javax.sql.ConnectionEvent;
import javax.sql.PooledConnection;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * PostgreSQL implementation of the PooledConnection interface.  This shouldn't
 * be used directly, as the pooling client should just interact with the
 * ConnectionPool instead.
 * @see PGConnectionPoolDataSource
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 * @author Csaba Nagy (ncsaba@yahoo.com)
 */
public class PGPooledConnection
        extends AbstractJdbc4PooledConnection
        implements PooledConnection
{

    public PGPooledConnection(Connection con, boolean autoCommit, boolean isXA)
    {
        super(con, autoCommit, isXA);
    }

    public PGPooledConnection(Connection con, boolean autoCommit)
    {
        this(con, autoCommit, false);
    }

    protected ConnectionEvent createConnectionEvent(SQLException sqle)
    {
        return new ConnectionEvent(this, sqle);
    }

}
