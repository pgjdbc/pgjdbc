/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
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

