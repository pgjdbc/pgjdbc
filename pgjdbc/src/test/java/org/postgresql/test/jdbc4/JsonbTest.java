/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

@ParameterizedClass(name = "binary = {0}")
@MethodSource("data")
public class JsonbTest extends BaseTest4 {
  public JsonbTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    assumeTrue(
        TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_4),
        "jsonb requires PostgreSQL 9.4+");
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

  @Test
  public void jsonbArray() throws SQLException {
    jsonArrayGet("jsonb", String.class);
  }

  @Test
  public void jsonArray() throws SQLException {
    jsonArrayGet("json", String.class);
  }

  private void jsonArrayGet(String type, Class<?> arrayElement) throws SQLException {
    PreparedStatement stmt = con.prepareStatement("SELECT '{[2],[3]}'::" + type + "[]");
    ResultSet rs = stmt.executeQuery();
    assertTrue(rs.next());
    Array array = rs.getArray(1);
    Object[] objectArray = (Object[]) array.getArray();
    assertEquals(
        "[[2], [3]]",
        Arrays.deepToString(objectArray),
        () -> "'{[2],[3]}'::" + type + "[] should come up as Java array with two entries");

    assertEquals(
        arrayElement.getName() + ", " + arrayElement.getName(),
        objectArray[0].getClass().getName() + ", " + objectArray[1].getClass().getName(),
        () -> type + " array entries should come up as strings");
    rs.close();
    stmt.close();
  }
}
