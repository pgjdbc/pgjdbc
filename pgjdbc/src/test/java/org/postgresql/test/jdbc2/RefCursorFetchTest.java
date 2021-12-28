/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.postgresql.PGConnection;

import org.junit.Test;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

public class RefCursorFetchTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();

    try (Statement statement = con.createStatement()) {
      statement.execute("create table if not exists  test_blob(content bytea)");
      statement.execute("--create function to read data\n"
          + "CREATE OR REPLACE FUNCTION test_blob(p_cur OUT REFCURSOR) AS $body$\n"
          + "BEGIN\n"
          + "OPEN p_cur FOR SELECT content FROM test_blob;\n"
          + "END;\n"
          + "$body$ LANGUAGE plpgsql STABLE");

      statement.execute("--generate 101 rows with 4096 bytes:\n"
          + "insert into test_blob\n"
          + "select(select decode(string_agg(lpad(to_hex(width_bucket(random(), 0, 1, 256) - 1), 2, '0'), ''), 'hex')FROM generate_series(1, 4096))\n"
          + "from generate_series (1, 101)");

    }
  }

  @Override
  public void tearDown() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("drop FUNCTION test_blob ()");
      stmt.execute("drop table test_blob");
    }
    super.tearDown();
  }

  @Test
  public void testRefCursorWithFetchSize() throws SQLException {
    assumeCallableStatementsSupported();
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
      } catch (SQLException ex) {
        fail(ex.getMessage());
      } finally {
        assertEquals(101, cnt);
      }
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
      } catch (SQLException ex) {
        fail(ex.getMessage());
      } finally {
        assertEquals(101, cnt);
      }
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
