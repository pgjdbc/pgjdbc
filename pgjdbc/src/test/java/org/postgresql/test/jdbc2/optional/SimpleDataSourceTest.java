/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2.optional;

import static org.junit.Assert.assertEquals;

import org.postgresql.PGProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.jdbc2.optional.SimpleDataSource;

import org.junit.Test;

import java.sql.SQLException;

/**
 * Performs the basic tests defined in the superclass. Just adds the configuration logic.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public class SimpleDataSourceTest extends BaseDataSourceTest {

  /**
   * Creates and configures a new SimpleDataSource.
   */
  @Override
  protected void initializeDataSource() {
    if (bds == null) {
      bds = new SimpleDataSource();
      setupDataSource(bds);
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testTypoPostgresUrl() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    // this should fail because the protocol is wrong.
    ds.setUrl("jdbc:postgres://localhost:5432/test");
  }

  @Test
  public void testGetUrlWithDeprecatedProperties() throws SQLException {
    String[][] testData = new String[][]{
        {"host7", "6543", "jdbc:postgresql://host7:6543/dbname4"}, // one host, one port
        {"host7,host8", "6543,9876", "jdbc:postgresql://host7:6543,host8:9876/dbname4"} // multi host, multi port
    };
    for (String[] testCase : testData) {
      String hosts = testCase[0];
      String ports = testCase[1];
      String result = testCase[2];
      // non-deprecated properties
      PGSimpleDataSource source = new PGSimpleDataSource();
      source.setProperty(PGProperty.HOST.getName(), hosts);
      source.setProperty(PGProperty.PORT.getName(), ports);
      source.setProperty(PGProperty.DBNAME.getName(), "dbname4");
      assertEquals("url", result, source.getURL());
      // deprecated properties
      source = new PGSimpleDataSource();
      source.setProperty(PGProperty.PG_HOST.getName(), hosts);
      source.setProperty(PGProperty.PG_PORT.getName(), ports);
      source.setProperty(PGProperty.PG_DBNAME.getName(), "dbname4");
      assertEquals("url", result, source.getURL());
    }
  }
}
