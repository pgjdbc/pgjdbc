/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2011, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.xa.jdbc4;

import java.sql.SQLFeatureNotSupportedException;

import legacy.org.postgresql.Driver;
import legacy.org.postgresql.xa.jdbc3.AbstractJdbc3XADataSource;

public class AbstractJdbc4XADataSource
    extends AbstractJdbc3XADataSource
{

    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException
    {
        throw Driver.notImplemented(this.getClass(), "getParentLogger()");
    }

}

