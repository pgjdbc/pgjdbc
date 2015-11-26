/*-------------------------------------------------------------------------
*
* Copyright (c) 2008-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.sql.SQLXML;

import org.postgresql.core.BaseConnection;

public class Jdbc4SQLXML extends AbstractJdbc4SQLXML implements SQLXML
{

    public Jdbc4SQLXML(BaseConnection conn)
    {
        super(conn);
    }

    public Jdbc4SQLXML(BaseConnection conn, String data)
    {
        super(conn, data);
    }

}
