/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.ds;

import legacy.org.postgresql.ds.jdbc4.AbstractJdbc4ConnectionPoolDataSource;

import javax.sql.ConnectionPoolDataSource;

/**
 * PostgreSQL implementation of ConnectionPoolDataSource.  The app server or
 * middleware vendor should provide a DataSource implementation that takes advantage
 * of this ConnectionPoolDataSource.  If not, you can use the PostgreSQL implementation
 * known as PoolingDataSource, but that should only be used if your server or middleware
 * vendor does not provide their own.  Why? The server may want to reuse the same
 * Connection across all EJBs requesting a Connection within the same Transaction, or
 * provide other similar advanced features.
 *
 * <p>In any case, in order to use this ConnectionPoolDataSource, you must set the property
 * databaseName.  The settings for serverName, portNumber, user, and password are
 * optional.  Note: these properties are declared in the superclass.</p>
 *
 * <p>This implementation supports JDK 1.3 and higher.</p>
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public class PGConnectionPoolDataSource
    extends AbstractJdbc4ConnectionPoolDataSource
    implements ConnectionPoolDataSource
{

}

