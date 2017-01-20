/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

public class DatabaseMetaDataTest {

  private Connection _conn;

  @Before
  public void setUp() throws Exception {
    _conn = TestUtil.openDB();
    TestUtil.dropSequence(_conn, "sercoltest_a_seq");
    TestUtil.createTable(_conn, "sercoltest", "a serial, b int");
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropSequence(_conn, "sercoltest_a_seq");
    TestUtil.dropTable(_conn, "sercoltest");
    TestUtil.closeDB(_conn);
  }

  @Test
  public void testGetClientInfoProperties() throws Exception {
    DatabaseMetaData dbmd = _conn.getMetaData();

    ResultSet rs = dbmd.getClientInfoProperties();
    if (!TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v9_0)) {
      assertTrue(!rs.next());
      return;
    }

    assertTrue(rs.next());
    assertEquals("ApplicationName", rs.getString("NAME"));
  }

  @Test
  public void testGetColumnsForAutoIncrement() throws Exception {
    DatabaseMetaData dbmd = _conn.getMetaData();

    ResultSet rs = dbmd.getColumns("%", "%", "sercoltest", "%");
    assertTrue(rs.next());
    assertEquals("a", rs.getString("COLUMN_NAME"));
    assertEquals("YES", rs.getString("IS_AUTOINCREMENT"));

    assertTrue(rs.next());
    assertEquals("b", rs.getString("COLUMN_NAME"));
    assertEquals("NO", rs.getString("IS_AUTOINCREMENT"));

    assertTrue(!rs.next());
  }

  @Test
  public void testGetSchemas() throws SQLException {
    DatabaseMetaData dbmd = _conn.getMetaData();

    ResultSet rs = dbmd.getSchemas("", "publ%");

    assertTrue(rs.next());
    assertEquals("public", rs.getString("TABLE_SCHEM"));
    assertNull(rs.getString("TABLE_CATALOG"));
    assertTrue(!rs.next());
  }
}
