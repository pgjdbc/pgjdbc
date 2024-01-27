/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4.jdbc41;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

class NetworkTimeoutTest {
  @Test
  void setNetworkTimeout() throws Exception {
    Connection conn = TestUtil.openDB();
    assertDoesNotThrow(() -> {
      conn.setNetworkTimeout(null, 0);
    }, "Connection.setNetworkTimeout() throw exception");
  }

  @Test
  void setNetworkTimeoutInvalid() throws Exception {
    Connection conn = TestUtil.openDB();
    try {
      conn.setNetworkTimeout(null, -1);
      fail("Connection.setNetworkTimeout() did not throw expected exception");
    } catch (SQLException e) {
      // Passed
    } finally {
      TestUtil.closeDB(conn);
    }
  }

  @Test
  void setNetworkTimeoutValid() throws Exception {
    Connection conn = TestUtil.openDB();
    assertDoesNotThrow(() -> {
      conn.setNetworkTimeout(null, (int) TimeUnit.SECONDS.toMillis(5));
      assertEquals(TimeUnit.SECONDS.toMillis(5), conn.getNetworkTimeout());
    }, "Connection.setNetworkTimeout() throw exception");
  }

  @Test
  void setNetworkTimeoutEnforcement() throws Exception {
    Connection conn = TestUtil.openDB();
    Statement stmt = null;
    try {
      conn.setNetworkTimeout(null, (int) TimeUnit.SECONDS.toMillis(1));
      stmt = conn.createStatement();
      stmt.execute("SELECT pg_sleep(2)");
      fail("Connection.setNetworkTimeout() did not throw expected exception");
    } catch (SQLException e) {
      // assertTrue(stmt.isClosed());
      assertTrue(conn.isClosed());
    } finally {
      TestUtil.closeQuietly(stmt);
      TestUtil.closeDB(conn);
    }
  }
}
