/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3/Jdbc3ResultSetMetaData.java,v 1.6 2004/11/09 08:50:37 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3;

import org.postgresql.core.*;

public class Jdbc3ResultSetMetaData extends org.postgresql.jdbc2.AbstractJdbc2ResultSetMetaData implements java.sql.ResultSetMetaData
{

    public Jdbc3ResultSetMetaData(BaseConnection connection, Field[] fields)
    {
        super(connection, fields);
    }

}

