/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3g/Jdbc3gResultSetMetaData.java,v 1.3 2004/11/09 08:51:33 jurka Exp $
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

