/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;

import org.postgresql.PGConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

public class RefCursorFetchTest extends BaseTest4 {
  @BeforeClass
  public static void checkVersion() throws SQLException {
    TestUtil.assumeHaveMinimumServerVersion(ServerVersion.v9_0);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    assumeCallableStatementsSupported();

    TestUtil.dropTable(con, "test_blob");
    TestUtil.createTable(con, "test_blob", "content bytea");

    // Create a function to read the blob data
    TestUtil.execute("CREATE OR REPLACE FUNCTION test_blob (p_cur OUT REFCURSOR) AS\n"
        + "$body$\n"
        + "  BEGIN\n"
        + "    OPEN p_cur FOR SELECT content FROM test_blob;\n"
        + "  END;\n"
        + "$body$ LANGUAGE plpgsql STABLE", con);

    // Generate 101 rows with 4096 bytes
    TestUtil.execute("INSERT INTO test_blob (content)\n"
        + "SELECT (SELECT decode(string_agg(lpad(to_hex(width_bucket(random(), 0, 1, 256) - 1), 2, '0'), ''), 'hex')\n"
        + "        FROM generate_series(1, 4096)) AS content\n"
        + "FROM generate_series (1, 101)", con);
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.execute("DROP FUNCTION IF EXISTS test_blob (OUT REFCURSOR)", con);
    TestUtil.dropTable(con, "test_blob");
    super.tearDown();
  }

  @Test
  public void testRefCursorWithFetchSize() throws SQLException {
    ((PGConnection)con).setDefaultFetchSize(50);
    int cnt = 0;
    try (CallableStatement call = con.prepareCall("{? = call test_blob()}")) {
      con.setAutoCommit(false); // ref cursors only work if auto commit is off
      call.registerOutParameter(1, Types.REF_CURSOR);
      call.execute();
      try (ResultSet rs = (ResultSet) call.getObject(1)) {
        while (rs.next()) {
          cnt++;
        }
      }
      assertEquals(101, cnt);
    }
  }

  @Test
  public void testRefCursorWithOutFetchSize() throws SQLException {
    assumeCallableStatementsSupported();
    int cnt = 0;
    try (CallableStatement call = con.prepareCall("{? = call test_blob()}")) {
      con.setAutoCommit(false); // ref cursors only work if auto commit is off
      call.registerOutParameter(1, Types.REF_CURSOR);
      call.execute();
      try (ResultSet rs = (ResultSet) call.getObject(1)) {
        while (rs.next()) {
          cnt++;
        }
      }
      assertEquals(101, cnt);
    }
  }

  /*
  test to make sure that close in the result set does not attempt to get rid of the non-existent
  portal
   */
  @Test
  public void testRefCursorWithFetchSizeNoTransaction() throws SQLException {
    assumeCallableStatementsSupported();
    ((PGConnection)con).setDefaultFetchSize(50);
    int cnt = 0;
    try (CallableStatement call = con.prepareCall("{? = call test_blob()}")) {
      con.setAutoCommit(false); // ref cursors only work if auto commit is off
      call.registerOutParameter(1, Types.REF_CURSOR);
      call.execute();
      // end the transaction here, which will get rid of the refcursor
      con.setAutoCommit(true);
      // we should be able to read the first 50 as they were read before the tx was ended
      try (ResultSet rs = (ResultSet) call.getObject(1)) {
        while (rs.next()) {
          cnt++;
        }
      } catch (SQLException ex) {
        // should get an exception here as we try to read more but the portal is gone
        assertEquals("34000", ex.getSQLState());
      } finally {
        assertEquals(50, cnt);
      }
    }
  }

}
