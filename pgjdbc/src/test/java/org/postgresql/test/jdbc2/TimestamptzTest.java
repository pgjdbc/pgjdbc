/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Tests both db and java side that correct type are passed.
 */
@RunWith(Parameterized.class)
public class TimestamptzTest extends BaseTest4 {

  public TimestamptzTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @Parameterized.Parameters(name = "timestamptzAlways = {1}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    setTimestamptzAlways(TimestamptzAlways.YES);
    super.setUp();
  }

  @Override
  public void tearDown() throws SQLException {
    super.tearDown();
  }

  @Test
  public void testTypeOnDbSite() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(" SELECT pg_typeof(?) ")) {
      ps.setTimestamp(1, new Timestamp(System.currentTimeMillis()));

      try {
        ResultSet rs = ps.executeQuery();
        if (rs.next()) {
          String typeFromDb = rs.getString(1);
          System.out.printf("db type: %s\n",typeFromDb);
          assertTrue("timestamp with time zone".equalsIgnoreCase(typeFromDb));
        } else {
          fail("no result");
        }
      } catch (SQLException e) {
        fail(e.getMessage());
      }
    }
  }

  @Test
  public void testTypeOnJavaSite() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(" SELECT ? ")) {
      ps.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
      ResultSet rs = ps.executeQuery();
      ResultSetMetaData md = rs.getMetaData();
      assertEquals(java.sql.Types.TIMESTAMP_WITH_TIMEZONE, md.getColumnType(1)); // currently give instead TIMESTAMP
    }
  }
}
