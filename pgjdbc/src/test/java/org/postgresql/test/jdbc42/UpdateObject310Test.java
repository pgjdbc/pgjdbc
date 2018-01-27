/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class UpdateObject310Test {
  private Connection con;

  @Parameterized.Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {LocalDateTime.of(2017, 1, 2, 3, 4, 5, 6000).truncatedTo(ChronoUnit.MICROS), "timestamp_without_time_zone_column", "'2003-01-01'::TIMESTAMP"},
        {OffsetDateTime.of(2017, 1, 2, 3, 4, 5, 6000, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS), "timestamp_with_time_zone_column", "'2007-06-03'::TIMESTAMP"},
        {LocalDate.of(2017, 1, 2), "date_column", "'2003-01-01'::DATE"},
        {LocalTime.of(11, 12, 13).truncatedTo(ChronoUnit.MICROS), "time_without_time_zone_column", "'07:23'::TIME WITHOUT TIME ZONE"}
    });
  }

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createTableWithPrimaryKey(con, "table1", "timestamp_without_time_zone_column timestamp without time zone,"
        + "timestamp_with_time_zone_column timestamp with time zone,"
        + "date_column date,"
        + "time_without_time_zone_column time without time zone"
    );
  }

  @After
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "table1");
    TestUtil.closeDB(con);
  }

  private Object newValue;
  private String columnName;
  private String originalValue;

  public UpdateObject310Test(Object newValue, String columnName, String originalValue) {
    this.newValue = newValue;
    this.columnName = columnName;
    this.originalValue = originalValue;
  }

  /**
   * Test the behavior of updateObject for timestamp columns.
   */
  @Test
  public void test() throws SQLException {
    Statement s = con.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = null;

    try {
      s.executeUpdate("INSERT INTO table1(" + columnName + ") VALUES(" + originalValue + ")");
      rs = s.executeQuery(TestUtil.selectSQL("table1", "table1_id, " + columnName));
      rs.next();
      rs.updateObject(2, newValue);
      rs.updateRow();
      rs.close();

      rs = s.executeQuery(TestUtil.selectSQL("table1", columnName));
      rs.next();
      assertEquals(newValue, rs.getObject(1, newValue.getClass()));
    } finally {
      TestUtil.closeQuietly(rs);
      TestUtil.closeQuietly(s);
    }
  }

  /**
   * Test the behavior of updateObject for timestamp columns.
   */
  @Test
  public void testWrongClass() throws SQLException {
    Statement s = con.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = null;

    try {
      s.executeUpdate("INSERT INTO table1(" + columnName + ") VALUES(" + originalValue + ")");
      rs = s.executeQuery(TestUtil.selectSQL("table1", "table1_id, " + columnName));
      rs.next();
      rs.updateObject(2, 23);
      rs.updateRow();
      rs.close();

      fail("should have thrown an PSQLException because integers can't represent the date");
    } catch (PSQLException e) {
      assertEquals(PSQLState.DATATYPE_MISMATCH.getState(), e.getSQLState());
    } finally {
      TestUtil.closeQuietly(rs);
      TestUtil.closeQuietly(s);
    }
  }
}
