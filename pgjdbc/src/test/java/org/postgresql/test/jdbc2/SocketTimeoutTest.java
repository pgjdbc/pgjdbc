/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

class SocketTimeoutTest {

  @Test
  void socketTimeoutEnforcement() throws Exception {
    Properties properties = new Properties();
    PGProperty.SOCKET_TIMEOUT.set(properties, 1);

    Connection conn = TestUtil.openDB(properties);
    Statement stmt = null;
    try {
      stmt = conn.createStatement();
      stmt.execute("SELECT pg_sleep(2)");
      fail("Connection with socketTimeout did not throw expected exception");
    } catch (SQLException e) {
      assertTrue(conn.isClosed());
    } finally {
      TestUtil.closeQuietly(stmt);
      TestUtil.closeDB(conn);
    }
  }
}
