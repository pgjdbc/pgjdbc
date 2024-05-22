/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2.optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.postgresql.Driver.parseURL;

import org.postgresql.PGProperty;
import org.postgresql.jdbc2.optional.SimpleDataSource;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;

import java.util.Properties;

/**
 * Performs the basic tests defined in the superclass. Just adds the configuration logic.
 */
public class SimpleDataSourceWithSetURLTest extends BaseDataSourceTest {
  /**
   * Creates and configures a new SimpleDataSource using setURL method.
   */
  @Override
  protected void initializeDataSource() throws PSQLException {
    if (bds == null) {
      bds = new SimpleDataSource();
      bds.setURL(String.format("jdbc:postgresql://%s:%d/%s?prepareThreshold=%d", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(), TestUtil.getPrepareThreshold()));
      bds.setUser(TestUtil.getUser());
      bds.setPassword(TestUtil.getPassword());
      bds.setProtocolVersion(TestUtil.getProtocolVersion());
    }
  }

  @Test
  public void testGetURL() throws Exception {
    con = getDataSourceConnection();

    String url = bds.getURL();
    Properties properties = parseURL(url, null);

    assertEquals(TestUtil.getServer(), properties.getProperty(PGProperty.HOST.getName()));
    assertEquals(Integer.toString(TestUtil.getPort()), properties.getProperty(PGProperty.PORT.getName()));
    assertEquals(TestUtil.getDatabase(), properties.getProperty(PGProperty.DBNAME.getName()));
    assertEquals(Integer.toString(TestUtil.getPrepareThreshold()), properties.getProperty(PGProperty.PREPARE_THRESHOLD.getName()));
  }

  @Test
  public void testSetURL() throws Exception {
    initializeDataSource();

    assertEquals(TestUtil.getServer(), bds.getServerName());
    assertEquals(TestUtil.getPort(), bds.getPortNumber());
    assertEquals(TestUtil.getDatabase(), bds.getDatabaseName());
    assertEquals(TestUtil.getPrepareThreshold(), bds.getPrepareThreshold());
  }
}
