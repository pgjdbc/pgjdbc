/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc42;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest;

import java.sql.*;


public class PreparedStatementTest extends BaseTest {

  public PreparedStatementTest(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "datetable", "tstz timestamptz, ttz timetz");
  }

  protected void tearDown() throws SQLException {
    TestUtil.dropTable(con, "datetable");
    super.tearDown();
  }

  public void testSetNull() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO datetable (tstz, ttz) VALUES (?, ?)");

    pstmt.setNull(1, Types.TIMESTAMP_WITH_TIMEZONE);
    pstmt.setNull(2, Types.TIME_WITH_TIMEZONE);
    pstmt.executeUpdate();

    pstmt.setObject(1, null, Types.TIMESTAMP_WITH_TIMEZONE);
    pstmt.setObject(2, null, Types.TIME_WITH_TIMEZONE);
    pstmt.executeUpdate();

    pstmt.close();
  }
}
