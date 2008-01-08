/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc4/Jdbc4ResultSetMetaData.java,v 1.1 2006/06/08 10:34:52 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import org.postgresql.core.*;

public class Jdbc4ResultSetMetaData extends AbstractJdbc4ResultSetMetaData implements java.sql.ResultSetMetaData
{

    public Jdbc4ResultSetMetaData(BaseConnection connection, Field[] fields)
    {
        super(connection, fields);
    }

}

