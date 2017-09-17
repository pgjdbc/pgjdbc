/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
public class LogTest extends BaseTest4 {

  private String oldLevel;

  public LogTest(BinaryMode binaryMode) {
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

  public void setUp() throws Exception {
    oldLevel = System.getProperties().getProperty("loglevel");
    System.getProperties().setProperty("loglevel", "2");
    super.setUp();
  }

  public void tearDown() throws SQLException {
    super.tearDown();
    if (oldLevel == null) {
      System.getProperties().remove("loglevel");
    } else {
      System.getProperties().setProperty("loglevel", oldLevel);
    }
  }

  @Test
  public void reallyLargeArgumentsBreaksLogging() throws SQLException {
    String[] largeInput = new String[220];
    String largeString = String.format("%1048576s", " ");
    for (int i = 0; i < largeInput.length; i++) {
      largeInput[i] = largeString;
    }
    Array arr = con.createArrayOf("text", largeInput);
    PreparedStatement ps = con.prepareStatement("select t from unnest(?::text[]) t");
    ps.setArray(1, arr);
    ResultSet rs = ps.executeQuery();
    int x = 0;
    while (rs.next()) {
      x += 1;
      String found = rs.getString(1);
      Assert.assertEquals(largeString, found);
    }
    Assert.assertEquals(largeInput.length, x);
    TestUtil.closeQuietly(rs);
    TestUtil.closeQuietly(ps);
  }
}
