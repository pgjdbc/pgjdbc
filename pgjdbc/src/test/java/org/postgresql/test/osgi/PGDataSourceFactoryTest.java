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

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.osgi.service.jdbc.DataSourceFactory;

import java.sql.Driver;
import java.util.Properties;

import javax.sql.ConnectionPoolDataSource;
import javax.sql.DataSource;
import javax.sql.XADataSource;

public class PGDataSourceFactoryTest {

  private DataSourceFactory _dataSourceFactory;

  @Before
  public void createFactory() {
    _dataSourceFactory = new PGDataSourceFactory();
  }

  @Test
  public void testCreateDriverDefault() throws Exception {
    Driver driver = _dataSourceFactory.createDriver(null);
    Assert.assertTrue(driver instanceof org.postgresql.Driver);
  }

  @Test
  public void testCreateDataSourceDefault() throws Exception {
    DataSource dataSource = _dataSourceFactory.createDataSource(null);
    Assert.assertNotNull(dataSource);
  }

  @Test
  public void testCreateDataSourceSimple() throws Exception {
    Properties properties = new Properties();
    properties.put(DataSourceFactory.JDBC_DATABASE_NAME, "db");
    properties.put("currentSchema", "schema");
    DataSource dataSource = _dataSourceFactory.createDataSource(properties);
    Assert.assertNotNull(dataSource);
    Assert.assertTrue(dataSource instanceof SimpleDataSource);
    SimpleDataSource simpleDataSource = (SimpleDataSource) dataSource;
    Assert.assertEquals("db", simpleDataSource.getDatabaseName());
    Assert.assertEquals("schema", simpleDataSource.getCurrentSchema());
  }

  @Test
  public void testCreateDataSourcePooling() throws Exception {
    Properties properties = new Properties();
    properties.put(DataSourceFactory.JDBC_DATABASE_NAME, "db");
    properties.put(DataSourceFactory.JDBC_INITIAL_POOL_SIZE, "5");
    properties.put(DataSourceFactory.JDBC_MAX_POOL_SIZE, "10");
    DataSource dataSource = _dataSourceFactory.createDataSource(properties);
    Assert.assertNotNull(dataSource);
    Assert.assertTrue(dataSource instanceof PoolingDataSource);
    PoolingDataSource poolingDataSource = (PoolingDataSource) dataSource;
    Assert.assertEquals("db", poolingDataSource.getDatabaseName());
    Assert.assertEquals(5, poolingDataSource.getInitialConnections());
    Assert.assertEquals(10, poolingDataSource.getMaxConnections());
  }

  @Test
  public void testCreateConnectionPoolDataSourceDefault() throws Exception {
    ConnectionPoolDataSource dataSource = _dataSourceFactory.createConnectionPoolDataSource(null);
    Assert.assertNotNull(dataSource);
  }

  @Test
  public void testCreateConnectionPoolDataSourceConfigured() throws Exception {
    Properties properties = new Properties();
    properties.put(DataSourceFactory.JDBC_DATABASE_NAME, "db");
    ConnectionPoolDataSource dataSource =
        _dataSourceFactory.createConnectionPoolDataSource(properties);
    Assert.assertNotNull(dataSource);
    Assert.assertTrue(dataSource instanceof ConnectionPool);
    ConnectionPool connectionPoolDataSource = (ConnectionPool) dataSource;
    Assert.assertEquals("db", connectionPoolDataSource.getDatabaseName());
  }

  @Test
  public void testCreateXADataSourceDefault() throws Exception {
    XADataSource dataSource = _dataSourceFactory.createXADataSource(null);
    Assert.assertNotNull(dataSource);
  }

  @Test
  public void testCreateXADataSourceConfigured() throws Exception {
    Properties properties = new Properties();
    properties.put(DataSourceFactory.JDBC_DATABASE_NAME, "db");
    XADataSource dataSource = _dataSourceFactory.createXADataSource(properties);
    Assert.assertNotNull(dataSource);
    Assert.assertTrue(dataSource instanceof PGXADataSource);
    PGXADataSource xaDataSource = (PGXADataSource) dataSource;
    Assert.assertEquals("db", xaDataSource.getDatabaseName());
  }
}
