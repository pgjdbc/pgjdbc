/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc42;

import java.sql.SQLException;
import java.util.Properties;

import org.postgresql.jdbc4.AbstractJdbc4Connection;
import org.postgresql.util.HostSpec;

public abstract class AbstractJdbc42Connection extends AbstractJdbc4Connection
{

    protected AbstractJdbc42Connection(HostSpec[] hostSpecs, String user, String database, Properties info, String url) throws SQLException
    {
        super(hostSpecs, user, database, info, url);
    }

}
