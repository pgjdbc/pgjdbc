/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.ServerVersion;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Assume;
import org.junit.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class JsonbTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    Assume.assumeTrue("jsonb requires PostgreSQL 9.4+", TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_4));
    TestUtil.createTable(con, "jsonbtest", "detail jsonb");
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO jsonbtest (detail) VALUES ('{\"a\": 1}')");
    stmt.executeUpdate("INSERT INTO jsonbtest (detail) VALUES ('{\"b\": 1}')");
    stmt.executeUpdate("INSERT INTO jsonbtest (detail) VALUES ('{\"c\": 1}')");
    stmt.close();
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "jsonbtest");
    super.tearDown();
  }

  @Test
  public void testJsonbNonPreparedStatement() throws SQLException {
    Statement stmt = con.createStatement();

    ResultSet rs = stmt.executeQuery("SELECT count(1) FROM jsonbtest WHERE detail ? 'a' = false;");
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    rs.close();
    stmt.close();
  }

  @Test
  public void testJsonbPreparedStatement() throws SQLException {
    PreparedStatement stmt = con.prepareStatement("SELECT count(1) FROM jsonbtest WHERE detail ?? 'a' = false;");
    ResultSet rs = stmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    rs.close();
    stmt.close();
  }
}
