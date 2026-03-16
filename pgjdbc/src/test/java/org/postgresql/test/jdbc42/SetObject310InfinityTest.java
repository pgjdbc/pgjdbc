/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;

@ParameterizedClass
@MethodSource("data")
public class SetObject310InfinityTest extends BaseTest4 {

  public SetObject310InfinityTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>(2);
    for (BaseTest4.BinaryMode binaryMode : BaseTest4.BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @BeforeAll
  static void createTables() throws Exception {
    try (Connection conn = TestUtil.openDB()) {
      TestUtil.createTable(conn, "testsetobj310inf", "timestamp_without_time_zone_column timestamp without time zone,"
              + "timestamp_with_time_zone_column timestamp with time zone,"
              + "date_column date"
      );
    }
  }

  @AfterAll
  static void dropTables() throws Exception {
    try (Connection conn = TestUtil.openDB()) {
      TestUtil.dropTable(conn, "testsetobj310inf");
    }
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4), "PostgreSQL 8.3 does not support 'infinity' for 'date'");
    TestUtil.execute(con, "TRUNCATE testsetobj310inf");
  }

  @Test
  public void testTimestamptz() throws SQLException {
    runTestforType(OffsetDateTime.MAX, OffsetDateTime.MIN, "timestamp_without_time_zone_column", null);
  }

  @Test
  public void testTimestamp() throws SQLException {
    runTestforType(LocalDateTime.MAX, LocalDateTime.MIN, "timestamp_without_time_zone_column", null);
  }

  @Test
  public void testDate() throws SQLException {
    runTestforType(LocalDate.MAX, LocalDate.MIN, "date_column", null);
  }

  private void runTestforType(Object max, Object min, String columnName, Integer type) throws SQLException {
    insert(max, columnName, type);
    String readback = readString(columnName);
    assertEquals("infinity", readback);
    delete();

    insert(min, columnName, type);
    readback = readString(columnName);
    assertEquals("-infinity", readback);
    delete();
  }

  private void insert(Object data, String columnName, Integer type) throws SQLException {
    PreparedStatement ps = con.prepareStatement(TestUtil.insertSQL("testsetobj310inf", columnName, "?"));
    try {
      if (type != null) {
        ps.setObject(1, data, type);
      } else {
        ps.setObject(1, data);
      }
      assertEquals(1, ps.executeUpdate());
    } finally {
      ps.close();
    }
  }

  private String readString(String columnName) throws SQLException {
    Statement st = con.createStatement();
    try {
      ResultSet rs = st.executeQuery(TestUtil.selectSQL("testsetobj310inf", columnName));
      try {
        assertNotNull(rs);
        assertTrue(rs.next());
        return rs.getString(1);
      } finally {
        rs.close();
      }
    } finally {
      st.close();
    }
  }

  private void delete() throws SQLException {
    Statement st = con.createStatement();
    try {
      st.execute("DELETE FROM testsetobj310inf");
    } finally {
      st.close();
    }
  }

}
