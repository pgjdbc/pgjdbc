/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.postgresql.util.PSQLState.ERROR_CODE_CRASH_SHUTDOWN;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;

public class TestTerminate extends BaseTest4 {
  Connection con2;

  @Before
  public void setup() throws Exception {
    super.setUp();
    TestUtil.createTempTable(con, "bigselect", "i int, t text");
    TestUtil.execute( "insert into bigselect (i, t) values (generate_series(1,10), 'a;kjdf;ajd;akj;kj;kj;j')", con);
    con2 = TestUtil.openDB();
  }

  @After
  public void tearDown() throws SQLException {
    super.tearDown();
  }

  @Test
  public void testTerminate() throws Exception {
    try (Statement statement = con.createStatement()) {
      try ( ResultSet rs = statement.executeQuery( "select * from bigselect")) {
        try {
          con2.createStatement().execute("COPY (SELECT pg_backend_pid()) TO PROGRAM 'xargs kill -SIGSEGV';");
        } catch (Exception ex ) {
          // ignore this
        }
        while (rs.next()) {
          rs.getString(2);
        }
      }
      try (ResultSet rs = statement.executeQuery("select 1")) {
        fail("should not get here, above we have caused a fatal termination. This will close this connection");
      } catch (SQLException ex ) {
        SQLWarning sqlWarning = statement.getWarnings();
        assertNotNull(sqlWarning);
        assertEquals(sqlWarning.getSQLState(),ERROR_CODE_CRASH_SHUTDOWN.getState());
      }
    }
  }
}
