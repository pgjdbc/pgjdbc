/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc42;

import java.sql.SQLXML;

import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc4.AbstractJdbc4SQLXML;

public class Jdbc42SQLXML extends AbstractJdbc4SQLXML implements SQLXML
{

    public Jdbc42SQLXML(BaseConnection conn)
    {
        super(conn);
    }

    public Jdbc42SQLXML(BaseConnection conn, String data)
    {
        super(conn, data);
    }

}
