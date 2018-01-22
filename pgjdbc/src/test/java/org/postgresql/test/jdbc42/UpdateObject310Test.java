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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.TimeZone;

public class UpdateObject310Test {

  private static final TimeZone saveTZ = TimeZone.getDefault();

  private Connection con;

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();
    Statement s = con.createStatement();
    s.executeUpdate("DROP TABLE IF EXISTS UpdateObject310Test");
    s.executeUpdate("CREATE TABLE UpdateObject310Test (\n"
        + "  table_id SERIAL PRIMARY KEY,\n"
        + "  timestamp_without_time_zone_column TIMESTAMP WITHOUT TIME ZONE NULL"
        + ")");
    s.close();
  }

  @After
  public void tearDown() throws SQLException {
    TimeZone.setDefault(saveTZ);
    TestUtil.dropTable(con, "table1");
    TestUtil.closeDB(con);
  }

  /**
   * Test the behavior of setObject for timestamp columns.
   */
  @Test
  public void insert() throws SQLException {
    Statement s = con.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = null;

    LocalDateTime updateDate = LocalDateTime.now();
    try {
      s.executeUpdate(
          "insert into UpdateObject310Test(timestamp_without_time_zone_column) values"
              + "('2003-01-01'::timestamp)");
      rs = s.executeQuery(TestUtil
          .selectSQL("UpdateObject310Test", "table_id, timestamp_without_time_zone_column"));
      rs.next();
      rs.updateObject(2, updateDate);
      rs.updateRow();
      rs.close();

      rs = s.executeQuery(TestUtil
          .selectSQL("UpdateObject310Test", "table_id, timestamp_without_time_zone_column"));
      rs.next();
      assertEquals(updateDate, rs.getObject(2, LocalDateTime.class));
    } finally {
      if (rs != null) {
        rs.close();
      }
      s.close();
    }
  }

}
