/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/util/PSQLWarning.java,v 1.3 2004/11/09 08:57:34 jurka Exp $
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
