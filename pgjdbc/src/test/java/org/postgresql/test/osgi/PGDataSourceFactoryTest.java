/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.osgi;

import org.postgresql.jdbc2.optional.ConnectionPool;
import org.postgresql.jdbc2.optional.PoolingDataSource;
import org.postgresql.jdbc2.optional.SimpleDataSource;
import org.postgresql.osgi.PGDataSourceFactory;
import org.postgresql.xa.PGXADataSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.service.jdbc.DataSourceFactory;

import java.sql.Driver;
import java.util.Properties;

import javax.sql.ConnectionPoolDataSource;
import javax.sql.DataSource;
import javax.sql.XADataSource;

class PGDataSourceFactoryTest {

  private DataSourceFactory dataSourceFactory;

  @BeforeEach
  void createFactory() {
    dataSourceFactory = new PGDataSourceFactory();
  }

  @Test
  void createDriverDefault() throws Exception {
    Driver driver = dataSourceFactory.createDriver(null);
    Assertions.assertTrue(driver instanceof org.postgresql.Driver);
  }

  @Test
  void createDataSourceDefault() throws Exception {
    DataSource dataSource = dataSourceFactory.createDataSource(null);
    Assertions.assertNotNull(dataSource);
  }

  @Test
  void createDataSourceSimple() throws Exception {
    Properties properties = new Properties();
    properties.put(DataSourceFactory.JDBC_DATABASE_NAME, "db");
    properties.put("currentSchema", "schema");
    DataSource dataSource = dataSourceFactory.createDataSource(properties);
    Assertions.assertNotNull(dataSource);
    Assertions.assertTrue(dataSource instanceof SimpleDataSource);
    SimpleDataSource simpleDataSource = (SimpleDataSource) dataSource;
    Assertions.assertEquals("db", simpleDataSource.getDatabaseName());
    Assertions.assertEquals("schema", simpleDataSource.getCurrentSchema());
  }

  @Test
  void createDataSourcePooling() throws Exception {
    Properties properties = new Properties();
    properties.put(DataSourceFactory.JDBC_DATABASE_NAME, "db");
    properties.put(DataSourceFactory.JDBC_INITIAL_POOL_SIZE, "5");
    properties.put(DataSourceFactory.JDBC_MAX_POOL_SIZE, "10");
    DataSource dataSource = dataSourceFactory.createDataSource(properties);
    Assertions.assertNotNull(dataSource);
    Assertions.assertTrue(dataSource instanceof PoolingDataSource);
    PoolingDataSource poolingDataSource = (PoolingDataSource) dataSource;
    Assertions.assertEquals("db", poolingDataSource.getDatabaseName());
    Assertions.assertEquals(5, poolingDataSource.getInitialConnections());
    Assertions.assertEquals(10, poolingDataSource.getMaxConnections());
  }

  @Test
  void createConnectionPoolDataSourceDefault() throws Exception {
    ConnectionPoolDataSource dataSource = dataSourceFactory.createConnectionPoolDataSource(null);
    Assertions.assertNotNull(dataSource);
  }

  @Test
  void createConnectionPoolDataSourceConfigured() throws Exception {
    Properties properties = new Properties();
    properties.put(DataSourceFactory.JDBC_DATABASE_NAME, "db");
    ConnectionPoolDataSource dataSource =
        dataSourceFactory.createConnectionPoolDataSource(properties);
    Assertions.assertNotNull(dataSource);
    Assertions.assertTrue(dataSource instanceof ConnectionPool);
    ConnectionPool connectionPoolDataSource = (ConnectionPool) dataSource;
    Assertions.assertEquals("db", connectionPoolDataSource.getDatabaseName());
  }

  @Test
  void createXADataSourceDefault() throws Exception {
    XADataSource dataSource = dataSourceFactory.createXADataSource(null);
    Assertions.assertNotNull(dataSource);
  }

  @Test
  void createXADataSourceConfigured() throws Exception {
    Properties properties = new Properties();
    properties.put(DataSourceFactory.JDBC_DATABASE_NAME, "db");
    XADataSource dataSource = dataSourceFactory.createXADataSource(properties);
    Assertions.assertNotNull(dataSource);
    Assertions.assertTrue(dataSource instanceof PGXADataSource);
    PGXADataSource xaDataSource = (PGXADataSource) dataSource;
    Assertions.assertEquals("db", xaDataSource.getDatabaseName());
  }
}
