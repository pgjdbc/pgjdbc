/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/util/PSQLException.java,v 1.17 2008/09/30 03:42:48 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.util;

import java.sql.SQLException;

public class PSQLException extends SQLException
{

    private ServerErrorMessage _serverError;

    public PSQLException(String msg, PSQLState state, Throwable cause)
    {
        super(msg, state == null ? null : state.getState());
        initCause(cause);
    }

    public PSQLException(String msg, PSQLState state)
    {
        this(msg, state, null);
    }

    public PSQLException(ServerErrorMessage serverError)
    {
        this(serverError.toString(), new PSQLState(serverError.getSQLState()));
        _serverError = serverError;
    }

    public ServerErrorMessage getServerErrorMessage()
    {
        return _serverError;
    }

}
