/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertEquals;

import org.postgresql.test.TestUtil;

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
        {LocalDateTime.now(), "timestamp_without_time_zone_column", "'2003-01-01'::TIMESTAMP"},
        {LocalDate.now(), "date_column", "'2003-01-01'::DATE"},
        {LocalTime.now(), "time_without_time_zone_column", "'07:23'::TIME WITHOUT TIME ZONE"}
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

  public UpdateObject310Test(Object updateDate, String columnName, String originalValue) {
    this.updateDate = updateDate;
    this.columnName = columnName;
    this.originalValue = originalValue;
  }

  /**
   * Test the behavior of setObject for timestamp columns.
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
}
