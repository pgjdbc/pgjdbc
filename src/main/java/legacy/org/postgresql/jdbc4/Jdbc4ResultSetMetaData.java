/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc4;

import legacy.org.postgresql.core.BaseConnection;
import legacy.org.postgresql.core.Field;

public class Jdbc4ResultSetMetaData extends AbstractJdbc4ResultSetMetaData implements java.sql.ResultSetMetaData
{

    public Jdbc4ResultSetMetaData(BaseConnection connection, Field[] fields)
    {
        super(connection, fields);
    }

}

