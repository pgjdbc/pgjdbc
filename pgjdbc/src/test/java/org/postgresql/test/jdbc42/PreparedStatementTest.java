/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalTime;
import java.util.Properties;

public class PreparedStatementTest extends BaseTest4 {
  protected void updateProperties(Properties props) {
    PGProperty.PREFER_QUERY_MODE.set(props, "simple");
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "timestamptztable", "tstz timestamptz");
    TestUtil.createTable(con, "timetztable", "ttz timetz");
    TestUtil.createTable(con, "timetable", "id serial, tt time");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "timestamptztable");
    TestUtil.dropTable(con, "timetztable");
    TestUtil.dropTable(con, "timetable");
    super.tearDown();
  }

  @Test
  public void testSetNumber() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("SELECT ? * 2");

    pstmt.setBigDecimal(1, new BigDecimal("1.6"));
    ResultSet rs = pstmt.executeQuery();
    rs.next();
    BigDecimal d = rs.getBigDecimal(1);
    pstmt.close();

    assertEquals(new BigDecimal("3.2"), d);
  }

  @Test
  public void testSetBoolean() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement("select false union select (select ?)")) {
      ps.setBoolean(1, true);

      try (ResultSet rs = ps.executeQuery()) {
        assert (rs.next());
        rs.getBoolean(1);
      }
    }
  }

  @Test
  public void testTimestampTzSetNull() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO timestamptztable (tstz) VALUES (?)");

    // valid: fully qualified type to setNull()
    pstmt.setNull(1, Types.TIMESTAMP_WITH_TIMEZONE);
    pstmt.executeUpdate();

    // valid: fully qualified type to setObject()
    pstmt.setObject(1, null, Types.TIMESTAMP_WITH_TIMEZONE);
    pstmt.executeUpdate();

    pstmt.close();
  }

  @Test
  public void testTimeTzSetNull() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO timetztable (ttz) VALUES (?)");

    // valid: fully qualified type to setNull()
    pstmt.setNull(1, Types.TIME_WITH_TIMEZONE);
    pstmt.executeUpdate();

    // valid: fully qualified type to setObject()
    pstmt.setObject(1, null, Types.TIME_WITH_TIMEZONE);
    pstmt.executeUpdate();

    pstmt.close();
  }

  @Test
  public void testLocalTimeMax() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO timetable (tt) VALUES (?)");

    pstmt.setObject(1, LocalTime.MAX);
    pstmt.executeUpdate();

    pstmt.setObject(1, LocalTime.MIN);
    pstmt.executeUpdate();

    ResultSet rs = con.createStatement().executeQuery("select tt from timetable order by id asc");
    assertTrue(rs.next());
    LocalTime localTime = (LocalTime) rs.getObject(1, LocalTime.class);
    assertEquals(LocalTime.MAX, localTime);

    assertTrue(rs.next());
    localTime = (LocalTime) rs.getObject(1, LocalTime.class);
    assertEquals(LocalTime.MIN, localTime);
  }
}
