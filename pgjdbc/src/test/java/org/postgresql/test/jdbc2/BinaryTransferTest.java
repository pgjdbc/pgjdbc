/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Tests binaryTransfer.
 */
public class BinaryTransferTest {
  /**
   * The name of the test table.
   */
  private static final String TEST_TABLE = "testbintrans";

  private Connection con;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty("binaryTransfer", Boolean.TRUE.toString());
    con = TestUtil.openDB(props);
    TestUtil.createTable(con, TEST_TABLE, "ts timestamp");
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropTable(con, TEST_TABLE);
    TestUtil.closeDB(con);
  }

  /**
   * Verifies that timestamps are correctly transferred in binary
   * transfer mode.  Mainly intended for float-timestamps.
   *
   * @throws SQLException if a JDBC or database problem occurs.
   */
  @Test
  public void verifyFloatTimestampTransfer() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT setting FROM pg_settings WHERE name = 'integer_datetimes'");
    assertTrue(rs.next());

    boolean is_float_datetime = !rs.getBoolean(1);
    rs.close();
	assumeTrue(is_float_datetime);

    String targettime = "2020-01-01 00:00:00.464";
    String inssql = "INSERT INTO " + TEST_TABLE
        + " VALUES ('" + targettime + "'::timestamp)";
    String selsql = "SELECT ts FROM " + TEST_TABLE;

    // Insert the timestamp as casted strings.
    assertEquals(stmt.executeUpdate(inssql), 1);

    // Insert the timestamps as PGTimestamp objects.
    PreparedStatement pstmt = con.prepareStatement(selsql);

	// Make sure to run server-side prepare
	int repeats = TestUtil.getPrepareThreshold() + 1;
    for (int i = 0 ; i < repeats ; i++) {
      rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(rs.getTimestamp(1).toString(), targettime);
    }

    // Clean up.
    assertEquals(1, stmt.executeUpdate("DELETE FROM " + TEST_TABLE));
    stmt.close();
    pstmt.close();
  }
}
