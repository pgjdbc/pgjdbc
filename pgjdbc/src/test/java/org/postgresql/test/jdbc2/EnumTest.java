/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class EnumTest extends BaseTest4 {
  public EnumTest(BinaryMode binaryMode) {
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
    Assert.assertNotNull("{duplicate,new} should come up as a non-null array", array);
    Object[] objectArray = (Object[]) array.getArray();
    Assert.assertEquals(
        "{duplicate,new} should come up as Java array with two entries",
        "[duplicate, new]",
        Arrays.deepToString(objectArray)
    );

    Assert.assertEquals(
        "Enum array entries should come up as strings",
        "java.lang.String, java.lang.String",
        objectArray[0].getClass().getName() + ", " + objectArray[1].getClass().getName()
    );
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
    Assert.assertNotNull(value + " should come up as a non-null array", array);
    Object[] objectArray = (Object[]) array.getArray();
    Assert.assertEquals(
        value + " should come up as Java array with two entries",
        "[[duplicate, new], [spike, spike]]",
        Arrays.deepToString(objectArray)
    );

    Assert.assertEquals(
        "Enum array entries should come up as strings",
        "[Ljava.lang.String;, [Ljava.lang.String;",
        objectArray[0].getClass().getName() + ", " + objectArray[1].getClass().getName()
    );
    rs.close();
    pstmt.close();
  }
}
