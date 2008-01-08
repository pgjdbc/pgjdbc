/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/util/PSQLWarning.java,v 1.6 2005/04/10 16:44:13 jurka Exp $
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
