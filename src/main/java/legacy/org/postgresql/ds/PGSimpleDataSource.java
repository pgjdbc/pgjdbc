/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2004-2011, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */
package legacy.org.postgresql.ds;

import legacy.org.postgresql.ds.jdbc4.AbstractJdbc4SimpleDataSource;

import javax.sql.DataSource;

/**
 * Simple DataSource which does not perform connection pooling.  In order to use
 * the DataSource, you must set the property databaseName. The settings for
 * serverName, portNumber, user, and password are optional.  Note: these properties
 * are declared in the superclass.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public class PGSimpleDataSource
        extends AbstractJdbc4SimpleDataSource
        implements DataSource
{
}
