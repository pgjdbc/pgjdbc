/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/ds/jdbc4/AbstractJdbc4PooledConnection.java,v 1.1 2006/11/29 04:00:27 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.ds.jdbc4;

import java.sql.Connection;
import javax.sql.StatementEventListener;
import org.postgresql.ds.jdbc23.AbstractJdbc23PooledConnection;

public abstract class AbstractJdbc4PooledConnection extends AbstractJdbc23PooledConnection
{

    public AbstractJdbc4PooledConnection(Connection con, boolean autoCommit, boolean isXA)
    {
        super(con, autoCommit, isXA);
    }

    public void removeStatementEventListener(StatementEventListener listener)
    {
    }

    public void addStatementEventListener(StatementEventListener listener)
    {
    }

}
