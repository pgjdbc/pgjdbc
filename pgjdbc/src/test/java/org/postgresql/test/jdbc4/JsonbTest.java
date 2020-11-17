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

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class JsonbTest extends BaseTest4 {
  public JsonbTest(BinaryMode binaryMode) {
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
    Assert.assertEquals(
        "'{[2],[3]}'::" + type + "[] should come up as Java array with two entries",
        "[[2], [3]]",
        Arrays.deepToString(objectArray)
    );

    Assert.assertEquals(
        type + " array entries should come up as strings",
        arrayElement.getName() + ", " + arrayElement.getName(),
        objectArray[0].getClass().getName() + ", " + objectArray[1].getClass().getName()
    );
    rs.close();
    stmt.close();
  }
}
