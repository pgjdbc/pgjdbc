/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4.jdbc41;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.test.TestUtil;

import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

public class NetworkTimeoutTest {
  @Test
  public void testSetNetworkTimeout() throws Exception {
    Connection _conn = TestUtil.openDB();
    try {
      _conn.setNetworkTimeout(null, 0);
    } catch (SQLException e) {
      fail("Connection.setNetworkTimeout() throw exception");
    } finally {
      TestUtil.closeDB(_conn);
    }
  }

  @Test
  public void testSetNetworkTimeoutInvalid() throws Exception {
    Connection _conn = TestUtil.openDB();
    try {
      _conn.setNetworkTimeout(null, -1);
      fail("Connection.setNetworkTimeout() did not throw expected exception");
    } catch (SQLException e) {
      // Passed
    } finally {
      TestUtil.closeDB(_conn);
    }
  }

  @Test
  public void testSetNetworkTimeoutValid() throws Exception {
    Connection _conn = TestUtil.openDB();
    try {
      _conn.setNetworkTimeout(null, (int) TimeUnit.SECONDS.toMillis(5));
      assertEquals(TimeUnit.SECONDS.toMillis(5), _conn.getNetworkTimeout());
    } catch (SQLException e) {
      fail("Connection.setNetworkTimeout() throw exception");
    } finally {
      TestUtil.closeDB(_conn);
    }
  }

  @Test
  public void testSetNetworkTimeoutEnforcement() throws Exception {
    Connection _conn = TestUtil.openDB();
    Statement stmt = null;
    try {
      _conn.setNetworkTimeout(null, (int) TimeUnit.SECONDS.toMillis(1));
      stmt = _conn.createStatement();
      stmt.execute("SELECT pg_sleep(2)");
      fail("Connection.setNetworkTimeout() did not throw expected exception");
    } catch (SQLException e) {
      // assertTrue(stmt.isClosed());
      assertTrue(_conn.isClosed());
    } finally {
      TestUtil.closeQuietly(stmt);
      TestUtil.closeDB(_conn);
    }
  }
}
