/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Tests {@code INSERT .. ON CONFLICT} introduced in PostgreSQL 9.5.
 */
public class UpsertTest extends BaseTest {
  Statement stmt;

  public UpsertTest(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    super.setUp();

    TestUtil.createTempTable(con, "test_statement", "i int primary key, t varchar(5)");
    stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO test_statement(i, t) VALUES (42, '42')");
  }

  protected void tearDown() throws SQLException {
    stmt.close();
    TestUtil.dropTable(con, "test_statement");
    super.tearDown();
  }

  protected int executeUpdate(String sql) throws SQLException {
    PreparedStatement ps = con.prepareStatement(sql);
    int count = ps.executeUpdate();
    ps.close();
    return count;
  }

  public void testUpsertDoNothingConflict() throws SQLException {
    int count = executeUpdate(
        "INSERT INTO test_statement(i, t) VALUES (42, '42') ON CONFLICT DO NOTHING");
    assertEquals("insert on CONFLICT DO NOTHING should report 0 modified rows on CONFLICT",
        0, count);
  }

  public void testUpsertDoNothingNoConflict() throws SQLException {
    int count = executeUpdate(
        "INSERT INTO test_statement(i, t) VALUES (43, '43') ON CONFLICT DO NOTHING");
    assertEquals("insert on conflict DO NOTHING should report 1 modified row on plain insert",
        1, count);
  }

  public void testUpsertDoUpdateConflict() throws SQLException {
    int count = executeUpdate(
        "INSERT INTO test_statement(i, t) VALUES (42, '42') ON CONFLICT(i) DO UPDATE SET t='43'");
    assertEquals("insert ON CONFLICT DO UPDATE should report 1 modified row on CONFLICT",
        1, count);
  }

  public void testUpsertDoUpdateNoConflict() throws SQLException {
    int count = executeUpdate(
        "INSERT INTO test_statement(i, t) VALUES (43, '43') ON CONFLICT(i) DO UPDATE SET t='43'");
    assertEquals("insert on conflict do update should report 1 modified row on plain insert",
        1, count);
  }
}
