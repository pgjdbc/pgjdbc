/*-------------------------------------------------------------------------
*
* Copyright (c) 2004, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/util/PSQLWarning.java,v 1.2 2004/11/07 22:17:15 jurka Exp $
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
}
