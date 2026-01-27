/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2.optional;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.jdbc2.optional.SimpleDataSource;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;

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
  protected void initializeDataSource() throws PSQLException {
    if (bds == null) {
      bds = new SimpleDataSource();
      setupDataSource(bds);
    }
  }

  @Test
  public void testTypoPostgresUrl() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    String url = "jdbc:postgres://localhost:5432/test";
    assertThrows(
        IllegalArgumentException.class,
        () -> ds.setUrl(url),
        () -> "protocols is wrong when calling ds.setUrl(\"" + url + "\")");
  }
}
