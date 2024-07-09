/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertTrue;

import org.postgresql.PGStatement;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;

import org.junit.Assume;
import org.junit.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class HoldableCursorTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();

    Assume.assumeTrue("Server-prepared statements are not supported in simple protocol, thus ignoring the tests",
        preferQueryMode != PreferQueryMode.SIMPLE);

    Statement stmt = con.createStatement();

    TestUtil.createTable(con, "testsps", "id integer, value boolean");

    stmt.executeUpdate("INSERT INTO testsps VALUES (1,'t')");
    stmt.executeUpdate("INSERT INTO testsps VALUES (2,'t')");
    stmt.executeUpdate("INSERT INTO testsps VALUES (1,'t')");
    stmt.executeUpdate("INSERT INTO testsps VALUES (2,'t')");
    stmt.executeUpdate("INSERT INTO testsps VALUES (1,'t')");
    stmt.executeUpdate("INSERT INTO testsps VALUES (2,'f')");

    stmt.close();
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "testsps");
    super.tearDown();
  }

  private static void setUseServerPrepare(PreparedStatement pstmt, boolean flag) throws SQLException {
    pstmt.unwrap(PGStatement.class).setUseServerPrepare(flag);
  }

  @Test
  public void testHoldableCursors() throws  SQLException {
    try ( PreparedStatement pstmt = con.prepareStatement("SELECT * FROM testsps WHERE id = ? or id = ?", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT) ) {
      setUseServerPrepare(pstmt, true);
      assertTrue(pstmt.unwrap(PGStatement.class).isUseServerPrepare());

      // Test that basic functionality works
      // bind different datatypes
      pstmt.setFetchSize(3);
      pstmt.setInt(1, 1);
      pstmt.setLong(2, 2);
      try (ResultSet rs = pstmt.executeQuery()) {
        for (int j = 0; j < 2; j++) {
          for (int i = 3; i > 0; i--) {
            assertTrue(rs.next());
          }
        }
      }
    }
  }
}
