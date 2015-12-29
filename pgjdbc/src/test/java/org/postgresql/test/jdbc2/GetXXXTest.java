/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

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

public class GetXXXTest extends TestCase {
  Connection con = null;

  public GetXXXTest(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    super.setUp();

    con = TestUtil.openDB();
    TestUtil.createTempTable(con, "test_interval",
        "initial timestamp with time zone, final timestamp with time zone");
    PreparedStatement pstmt = con.prepareStatement(
        "insert into test_interval values (?,?)");
    Calendar cal = Calendar.getInstance();
    cal.add(Calendar.DAY_OF_YEAR, -1);

    pstmt.setTimestamp(1, new Timestamp(cal.getTime().getTime()));
    pstmt.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
    assertTrue(pstmt.executeUpdate() == 1);
    pstmt.close();
  }

  protected void tearDown() throws Exception {
    super.tearDown();
    TestUtil.dropTable(con, "test_interval");
    con.close();
  }

  public void testGetObject() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery(
        "select (final-initial) as diff from test_interval");
    while (rs.next()) {
      String str = (String) rs.getString(1);

      assertNotNull(str);
      Object obj = rs.getObject(1);
      assertNotNull(obj);
    }
  }

  public void testGetUDT() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("select (final-initial) as diff from test_interval");

    while (rs.next()) {
      // make this return a PGobject
      Object obj = rs.getObject(1, new HashMap<String, Class<?>>());

      // it should not be an instance of PGInterval
      assertTrue(obj instanceof org.postgresql.util.PGInterval);

    }

  }

}
