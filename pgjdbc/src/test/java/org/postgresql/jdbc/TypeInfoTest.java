/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.junit.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.v3.BatchedQuery;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.test.jdbc2.BatchExecuteTest;

import java.lang.reflect.Method;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Properties;

import static org.junit.Assert.assertEquals;

/**
 * This object tests the internals of the TypeInfoCache.
 * Rather than rely on testing at the jdbc api layer.
 */
public class TypeInfoTest extends BaseTest4 {

  /*
   * Set up the fixture for this testcase: a connection to a database with a
   * table for this test.
   */
  @Override
  public void setUp() throws Exception {
    super.setUp();
    con.setAutoCommit(false);
  }

  // Tear down the fixture for this test case.
  @Override
  public void tearDown() throws SQLException {
    super.tearDown();
  }

  @Test
  public void testGetSqlTypeForNonExistingArrayType() throws Exception {
    int unknownLength = Integer.MAX_VALUE;
    BaseConnection pg = con.unwrap(BaseConnection.class);
    TypeInfoCache cache = new TypeInfoCache(pg, unknownLength);

    assertEquals(cache.getPGType("madeuptype[]"), 0);
  }

  @Test
  public void testGetSqlTypeForCachedArrayType() throws Exception {
    int unknownLength = Integer.MAX_VALUE;
    BaseConnection pg = con.unwrap(BaseConnection.class);
    TypeInfoCache cache = new TypeInfoCache(pg, unknownLength);

    assertEquals(cache.getPGType("text[]"), 1009);
  }

  @Test
  public void testGetSqlTypeForUncachedArrayType() throws Exception {
    int unknownLength = Integer.MAX_VALUE;
    BaseConnection pg = con.unwrap(BaseConnection.class);
    TypeInfoCache cache = new TypeInfoCache(pg, unknownLength);

    assertEquals(cache.getPGType("box[]"), 1020);
  }
}
