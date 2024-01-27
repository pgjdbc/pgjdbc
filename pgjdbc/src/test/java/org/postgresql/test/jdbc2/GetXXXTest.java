/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PGInterval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.HashMap;

/*
* Test for getObject
*/
class GetXXXTest {
  private Connection con;

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createTempTable(con, "test_interval",
        "initial timestamp with time zone, final timestamp with time zone");
    PreparedStatement pstmt = con.prepareStatement("insert into test_interval values (?,?)");
    Calendar cal = Calendar.getInstance();
    cal.add(Calendar.DAY_OF_YEAR, -1);

    pstmt.setTimestamp(1, new Timestamp(cal.getTime().getTime()));
    pstmt.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
    assertEquals(1, pstmt.executeUpdate());
    pstmt.close();
  }

  @AfterEach
  void tearDown() throws Exception {
    TestUtil.dropTable(con, "test_interval");
    con.close();
  }

  @Test
  void getObject() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("select (final-initial) as diff from test_interval");
    while (rs.next()) {
      String str = rs.getString(1);

      assertNotNull(str);
      Object obj = rs.getObject(1);
      assertNotNull(obj);
    }
  }

  @Test
  void getUDT() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("select (final-initial) as diff from test_interval");

    while (rs.next()) {
      // make this return a PGobject
      Object obj = rs.getObject(1, new HashMap<>());

      // it should not be an instance of PGInterval
      assertTrue(obj instanceof PGInterval);

    }

  }

}
