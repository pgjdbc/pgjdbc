/*-------------------------------------------------------------------------
*
* Copyright (c) 2004, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3g/Jdbc3gResultSetMetaData.java,v 1.2 2004/11/07 22:16:36 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3g;

import org.postgresql.core.*;

public class Jdbc3gResultSetMetaData extends org.postgresql.jdbc2.AbstractJdbc2ResultSetMetaData implements java.sql.ResultSetMetaData
{

    public Jdbc3gResultSetMetaData(BaseConnection connection, Field[] fields)
    {
        super(connection, fields);
    }

}

