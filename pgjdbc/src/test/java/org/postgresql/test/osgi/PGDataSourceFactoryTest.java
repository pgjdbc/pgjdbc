/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.osgi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.osgi.PGDataSourceFactory;
import org.postgresql.xa.PGXADataSource;

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
    assertTrue(driver instanceof org.postgresql.Driver);
  }

  @Test
  void createDataSourceDefault() throws Exception {
    DataSource dataSource = dataSourceFactory.createDataSource(null);
    assertNotNull(dataSource);
  }

  @Test
  @SuppressWarnings("deprecation")
  void createDataSourceSimple() throws Exception {
    Properties properties = new Properties();
    properties.put(DataSourceFactory.JDBC_DATABASE_NAME, "db");
    properties.put("currentSchema", "schema");
    DataSource dataSource = dataSourceFactory.createDataSource(properties);
    assertNotNull(dataSource);
    assertInstanceOf(org.postgresql.jdbc2.optional.SimpleDataSource.class, dataSource);
    org.postgresql.jdbc2.optional.SimpleDataSource simpleDataSource =
        (org.postgresql.jdbc2.optional.SimpleDataSource) dataSource;
    assertEquals("db", simpleDataSource.getDatabaseName());
    assertEquals("schema", simpleDataSource.getCurrentSchema());
  }

  @Test
  @SuppressWarnings("deprecation")
  void createDataSourcePooling() throws Exception {
    Properties properties = new Properties();
    properties.put(DataSourceFactory.JDBC_DATABASE_NAME, "db");
    properties.put(DataSourceFactory.JDBC_INITIAL_POOL_SIZE, "5");
    properties.put(DataSourceFactory.JDBC_MAX_POOL_SIZE, "10");
    DataSource dataSource = dataSourceFactory.createDataSource(properties);
    assertNotNull(dataSource);
    assertInstanceOf(org.postgresql.jdbc2.optional.PoolingDataSource.class, dataSource);
    org.postgresql.jdbc2.optional.PoolingDataSource poolingDataSource =
        (org.postgresql.jdbc2.optional.PoolingDataSource) dataSource;
    assertEquals("db", poolingDataSource.getDatabaseName());
    assertEquals(5, poolingDataSource.getInitialConnections());
    assertEquals(10, poolingDataSource.getMaxConnections());
  }

  @Test
  void createConnectionPoolDataSourceDefault() throws Exception {
    ConnectionPoolDataSource dataSource = dataSourceFactory.createConnectionPoolDataSource(null);
    assertNotNull(dataSource);
  }

  @Test
  @SuppressWarnings("deprecation")
  void createConnectionPoolDataSourceConfigured() throws Exception {
    Properties properties = new Properties();
    properties.put(DataSourceFactory.JDBC_DATABASE_NAME, "db");
    ConnectionPoolDataSource dataSource =
        dataSourceFactory.createConnectionPoolDataSource(properties);
    assertNotNull(dataSource);
    assertInstanceOf(org.postgresql.jdbc2.optional.ConnectionPool.class, dataSource);
    org.postgresql.jdbc2.optional.ConnectionPool connectionPoolDataSource =
        (org.postgresql.jdbc2.optional.ConnectionPool) dataSource;
    assertEquals("db", connectionPoolDataSource.getDatabaseName());
  }

  @Test
  void createXADataSourceDefault() throws Exception {
    XADataSource dataSource = dataSourceFactory.createXADataSource(null);
    assertNotNull(dataSource);
  }

  @Test
  void createXADataSourceConfigured() throws Exception {
    Properties properties = new Properties();
    properties.put(DataSourceFactory.JDBC_DATABASE_NAME, "db");
    XADataSource dataSource = dataSourceFactory.createXADataSource(properties);
    assertNotNull(dataSource);
    assertTrue(dataSource instanceof PGXADataSource);
    PGXADataSource xaDataSource = (PGXADataSource) dataSource;
    assertEquals("db", xaDataSource.getDatabaseName());
  }
}
