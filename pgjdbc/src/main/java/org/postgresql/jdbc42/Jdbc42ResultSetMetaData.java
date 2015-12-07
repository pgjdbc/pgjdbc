/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc42;

import java.sql.ResultSetMetaData;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Field;
import org.postgresql.jdbc4.AbstractJdbc4ResultSetMetaData;

public class Jdbc42ResultSetMetaData extends AbstractJdbc4ResultSetMetaData implements ResultSetMetaData
{

    public Jdbc42ResultSetMetaData(BaseConnection connection, Field[] fields)
    {
        super(connection, fields);
    }

}
