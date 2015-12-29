/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.osgi;

import org.postgresql.ds.common.BaseDataSource;
import org.postgresql.jdbc2.optional.ConnectionPool;
import org.postgresql.jdbc2.optional.PoolingDataSource;
import org.postgresql.jdbc2.optional.SimpleDataSource;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.xa.PGXADataSource;

import org.osgi.service.jdbc.DataSourceFactory;

import java.sql.SQLException;
import java.util.Map.Entry;
import java.util.Properties;
import javax.sql.ConnectionPoolDataSource;
import javax.sql.DataSource;
import javax.sql.XADataSource;

/**
 * This factory service is designed to be used in OSGi Enterprise environments to create and
 * configure JDBC data-sources.
 */
public class PGDataSourceFactory implements DataSourceFactory {

  /**
   * A class that removes properties as they are used (without modifying the supplied initial
   * Properties)
   */
  private static class SingleUseProperties extends Properties {
    private static final long serialVersionUID = 1L;

    public SingleUseProperties(Properties initialProperties) {
      super();
      if (initialProperties != null) {
        putAll(initialProperties);
      }
    }

    @Override
    public String getProperty(String key) {
      String value = super.getProperty(key);
      remove(key);
      return value;
    }
  }

  private void configureBaseDataSource(BaseDataSource ds, Properties props)
      throws SQLException {
    if (props.containsKey(JDBC_URL)) {
      ds.setUrl(props.getProperty(JDBC_URL));
    }
    if (props.containsKey(JDBC_SERVER_NAME)) {
      ds.setServerName(props.getProperty(JDBC_SERVER_NAME));
    }
    if (props.containsKey(JDBC_PORT_NUMBER)) {
      ds.setPortNumber(Integer.parseInt(props.getProperty(JDBC_PORT_NUMBER)));
    }
    if (props.containsKey(JDBC_DATABASE_NAME)) {
      ds.setDatabaseName(props.getProperty(JDBC_DATABASE_NAME));
    }
    if (props.containsKey(JDBC_USER)) {
      ds.setUser(props.getProperty(JDBC_USER));
    }
    if (props.containsKey(JDBC_PASSWORD)) {
      ds.setPassword(props.getProperty(JDBC_PASSWORD));
    }

    for (Entry<Object, Object> entry : props.entrySet()) {
      ds.setProperty((String) entry.getKey(), (String) entry.getValue());
    }
  }

  public java.sql.Driver createDriver(Properties props) throws SQLException {
    if (props != null && !props.isEmpty()) {
      throw new PSQLException(GT.tr("Unsupported properties: {0}", props.stringPropertyNames()),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    org.postgresql.Driver driver = new org.postgresql.Driver();
    return driver;
  }

  private DataSource createPoolingDataSource(Properties props) throws SQLException {
    PoolingDataSource dataSource = new PoolingDataSource();
    if (props.containsKey(JDBC_INITIAL_POOL_SIZE)) {
      dataSource.setInitialConnections(Integer.parseInt(props.getProperty(JDBC_INITIAL_POOL_SIZE)));
    }
    if (props.containsKey(JDBC_MAX_POOL_SIZE)) {
      dataSource.setMaxConnections(Integer.parseInt(props.getProperty(JDBC_MAX_POOL_SIZE)));
    }
    if (props.containsKey(JDBC_DATASOURCE_NAME)) {
      dataSource.setDataSourceName(props.getProperty(JDBC_DATASOURCE_NAME));
    }
    configureBaseDataSource(dataSource, props);
    return dataSource;
  }

  private DataSource createSimpleDataSource(Properties props) throws SQLException {
    SimpleDataSource dataSource = new SimpleDataSource();
    configureBaseDataSource(dataSource, props);
    return dataSource;
  }

  /**
   * Will create and return either a {@link SimpleDataSource} or a {@link PoolingDataSource}
   * depending on the presence in the supplied properties of any pool-related property (eg.: {@code
   * JDBC_INITIAL_POOL_SIZE} or {@code JDBC_MAX_POOL_SIZE})
   */
  public DataSource createDataSource(Properties props) throws SQLException {
    props = new SingleUseProperties(props);
    if (props.containsKey(JDBC_INITIAL_POOL_SIZE)
        || props.containsKey(JDBC_MIN_POOL_SIZE)
        || props.containsKey(JDBC_MAX_POOL_SIZE)
        || props.containsKey(JDBC_MAX_IDLE_TIME)
        || props.containsKey(JDBC_MAX_STATEMENTS)) {
      return createPoolingDataSource(props);
    } else {
      return createSimpleDataSource(props);
    }
  }

  public ConnectionPoolDataSource createConnectionPoolDataSource(Properties props)
      throws SQLException {
    props = new SingleUseProperties(props);
    ConnectionPool dataSource = new ConnectionPool();
    configureBaseDataSource(dataSource, props);
    return dataSource;
  }

  public XADataSource createXADataSource(Properties props)
      throws SQLException {
    props = new SingleUseProperties(props);
    PGXADataSource dataSource = new PGXADataSource();
    configureBaseDataSource(dataSource, props);
    return dataSource;
  }
}
