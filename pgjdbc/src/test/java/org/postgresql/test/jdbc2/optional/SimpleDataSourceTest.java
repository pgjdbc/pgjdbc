/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2.optional;

import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.jdbc2.optional.SimpleDataSource;

import org.junit.Test;

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
}
