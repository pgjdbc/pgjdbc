/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/util/PSQLWarning.java,v 1.5 2005/02/10 19:53:17 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.util;

import java.sql.SQLWarning;

public class PSQLWarning extends SQLWarning
{

    private ServerErrorMessage serverError;

    public PSQLWarning(ServerErrorMessage err)
    {
        this.serverError = err;
    }

    public String toString()
    {
        return serverError.toString();
    }

    public String getSQLState()
    {
        return serverError.getSQLState();
    }

    public String getMessage()
    {
        return serverError.getMessage();
    }

    public ServerErrorMessage getServerErrorMessage()
    {
        return serverError;
    }
}
