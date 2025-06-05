/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

@ParameterizedClass
@MethodSource("data")
public class EnumTest extends BaseTest4 {
  public EnumTest(BinaryMode binaryMode) {
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
    TestUtil.createEnumType(con, "flag", "'duplicate','spike','new'");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropType(con, "flag");
    super.tearDown();
  }

  @Test
  public void enumArray() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("SELECT '{duplicate,new}'::flag[]");
    ResultSet rs = pstmt.executeQuery();
    rs.next();
    Array array = rs.getArray(1);
    assertNotNull(array, "{duplicate,new} should come up as a non-null array");
    Object[] objectArray = (Object[]) array.getArray();
    assertEquals(
        "[duplicate, new]",
        Arrays.deepToString(objectArray),
        "{duplicate,new} should come up as Java array with two entries");

    assertEquals(
        "java.lang.String, java.lang.String",
        objectArray[0].getClass().getName() + ", " + objectArray[1].getClass().getName(),
        "Enum array entries should come up as strings");
    rs.close();
    pstmt.close();
  }

  @Test
  public void enumArrayArray() throws SQLException {
    String value = "{{duplicate,new},{spike,spike}}";
    PreparedStatement pstmt = con.prepareStatement("SELECT '" + value + "'::flag[][]");
    ResultSet rs = pstmt.executeQuery();
    rs.next();
    Array array = rs.getArray(1);
    assertNotNull(array, value + " should come up as a non-null array");
    Object[] objectArray = (Object[]) array.getArray();
    assertEquals(
        "[[duplicate, new], [spike, spike]]",
        Arrays.deepToString(objectArray),
        () -> value + " should come up as Java array with two entries");

    assertEquals(
        "[Ljava.lang.String;, [Ljava.lang.String;",
        objectArray[0].getClass().getName() + ", " + objectArray[1].getClass().getName(),
        "Enum array entries should come up as strings");
    rs.close();
    pstmt.close();
  }
}
