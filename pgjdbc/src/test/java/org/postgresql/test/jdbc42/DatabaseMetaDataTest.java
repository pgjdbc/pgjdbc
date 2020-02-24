/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

public class DatabaseMetaDataTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    conn = TestUtil.openDB();
    TestUtil.createTable(conn, "decimaltest", "a decimal, b decimal(10, 5)");
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropTable(conn, "decimaltest");
    TestUtil.closeDB(conn);
  }

  @Test
  public void testGetColumnsForNullScale() throws Exception {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getColumns("%", "%", "decimaltest", "%");
    assertTrue(rs.next());
    assertEquals("a", rs.getString("COLUMN_NAME"));
    assertEquals(0, rs.getInt("DECIMAL_DIGITS"));
    assertTrue(rs.wasNull());

    assertTrue(rs.next());
    assertEquals("b", rs.getString("COLUMN_NAME"));
    assertEquals(5, rs.getInt("DECIMAL_DIGITS"));
    assertFalse(rs.wasNull());

    assertTrue(!rs.next());
  }
}
