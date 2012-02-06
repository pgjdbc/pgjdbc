/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import org.postgresql.core.*;

public class Jdbc2ResultSetMetaData extends AbstractJdbc2ResultSetMetaData implements java.sql.ResultSetMetaData
{
    public Jdbc2ResultSetMetaData(BaseConnection connection, Field[] fields)
    {
        super(connection, fields);
    }
}

