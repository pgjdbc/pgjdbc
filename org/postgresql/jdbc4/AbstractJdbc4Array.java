/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.sql.*;
import org.postgresql.core.*;
import org.postgresql.jdbc2.AbstractJdbc2Array;

public abstract class AbstractJdbc4Array extends AbstractJdbc2Array
{

    public AbstractJdbc4Array(BaseConnection connection, int oid, byte[] fieldBytes) throws SQLException
    {
        super(connection, oid, fieldBytes);
    }

    public AbstractJdbc4Array(BaseConnection connection, int oid, String fieldString) throws SQLException
    {
        super(connection, oid, fieldString);
    }

    public void free() throws SQLException
    {
        connection = null;
        fieldString = null;
        fieldBytes = null;
        arrayList = null;
    }
}
