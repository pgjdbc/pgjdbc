/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

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
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.TimeZone;

@RunWith(Parameterized.class)
public class UpdateObject310Test {
  private static final TimeZone saveTZ = TimeZone.getDefault();

  private Connection con;

  @Parameterized.Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {LocalDateTime.now().truncatedTo(ChronoUnit.MICROS), "timestamp_without_time_zone_column",
            "'2003-01-01'::TIMESTAMP",
            "ERROR: column \"timestamp_without_time_zone_column\" is of type timestamp without time zone but expression is of type integer\n"
                + "  Hint: You will need to rewrite or cast the expression.\n"
                + "  Position: 59"},
        {LocalDate.now(), "date_column", "'2003-01-01'::DATE",
            "ERROR: column \"date_column\" is of type date but expression is of type integer\n"
                + "  Hint: You will need to rewrite or cast the expression.\n"
                + "  Position: 36"},
        {LocalTime.now().truncatedTo(ChronoUnit.MICROS), "time_without_time_zone_column",
            "'07:23'::TIME WITHOUT TIME ZONE",
            "ERROR: column \"time_without_time_zone_column\" is of type time without time zone but expression is of type integer\n"
                + "  Hint: You will need to rewrite or cast the expression.\n"
                + "  Position: 54"}
    });
  }

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createTableWithPrimaryKey(con, "table1", "timestamp_without_time_zone_column timestamp without time zone,"
        + "date_column date,"
        + "time_without_time_zone_column time without time zone"
    );
  }

  @After
  public void tearDown() throws SQLException {
    TimeZone.setDefault(saveTZ);
    TestUtil.dropTable(con, "table1");
    TestUtil.closeDB(con);
  }

  private Object updateDate;
  private String columnName;
  private String originalValue;
  private String errorMessage;

  public UpdateObject310Test(Object updateDate, String columnName, String originalValue, String errorMessage) {
    this.updateDate = updateDate;
    this.columnName = columnName;
    this.originalValue = originalValue;
    this.errorMessage = errorMessage;
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
      rs.updateObject(2, updateDate);
      rs.updateRow();
      rs.close();

      rs = s.executeQuery(TestUtil.selectSQL("table1", columnName));
      rs.next();
      assertEquals(updateDate, rs.getObject(1, updateDate.getClass()));
    } finally {
      if (rs != null) {
        rs.close();
      }
      s.close();
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

      fail("should have thrown an Exception");
    } catch (PSQLException e) {
      assertEquals(errorMessage, e.getMessage());
    } finally {
      if (rs != null) {
        rs.close();
      }
      s.close();
    }
  }
}
