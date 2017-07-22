/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;


/**
 * Tests {@code INSERT .. ON CONFLICT} introduced in PostgreSQL 9.5.
 */
@RunWith(Parameterized.class)
public class UpsertTest extends BaseTest4 {
  public UpsertTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @Parameterized.Parameters(name = "binary = {0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    assumeMinimumServerVersion(ServerVersion.v9_5);

    TestUtil.createTempTable(con, "test_statement", "i int primary key, t varchar(5)");
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO test_statement(i, t) VALUES (42, '42')");
    TestUtil.closeQuietly(stmt);
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "test_statement");
    super.tearDown();
  }

  protected int executeUpdate(String sql) throws SQLException {
    PreparedStatement ps = con.prepareStatement(sql);
    int count = ps.executeUpdate();
    ps.close();
    return count;
  }

  @Test
  public void testUpsertDoNothingConflict() throws SQLException {
    int count = executeUpdate(
        "INSERT INTO test_statement(i, t) VALUES (42, '42') ON CONFLICT DO NOTHING");
    assertEquals("insert on CONFLICT DO NOTHING should report 0 modified rows on CONFLICT",
        0, count);
  }

  @Test
  public void testUpsertDoNothingNoConflict() throws SQLException {
    int count = executeUpdate(
        "INSERT INTO test_statement(i, t) VALUES (43, '43') ON CONFLICT DO NOTHING");
    assertEquals("insert on conflict DO NOTHING should report 1 modified row on plain insert",
        1, count);
  }

  @Test
  public void testUpsertDoUpdateConflict() throws SQLException {
    int count = executeUpdate(
        "INSERT INTO test_statement(i, t) VALUES (42, '42') ON CONFLICT(i) DO UPDATE SET t='43'");
    assertEquals("insert ON CONFLICT DO UPDATE should report 1 modified row on CONFLICT",
        1, count);
  }

  @Test
  public void testUpsertDoUpdateNoConflict() throws SQLException {
    int count = executeUpdate(
        "INSERT INTO test_statement(i, t) VALUES (43, '43') ON CONFLICT(i) DO UPDATE SET t='43'");
    assertEquals("insert on conflict do update should report 1 modified row on plain insert",
        1, count);
  }
}
