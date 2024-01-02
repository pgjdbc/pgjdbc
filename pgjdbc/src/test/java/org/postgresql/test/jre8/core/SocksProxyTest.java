/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jre8.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * @author Joe Kutner on 10/9/17.
 *         Twitter: @codefinger
 */
class SocksProxyTest {

  @AfterEach
  void cleanup() {
    System.clearProperty("socksProxyHost");
    System.clearProperty("socksProxyPort");
    System.clearProperty("socksNonProxyHosts");
  }

  /**
   * Tests the connect method by connecting to the test database.
   */
  @Test
  void connectWithSocksNonProxyHost() throws Exception {
    System.setProperty("socksProxyHost", "fake-socks-proxy");
    System.setProperty("socksProxyPort", "9999");
    System.setProperty("socksNonProxyHosts", TestUtil.getServer());

    TestUtil.initDriver(); // Set up log levels, etc.

    Connection con =
        DriverManager.getConnection(TestUtil.getURL(), TestUtil.getUser(), TestUtil.getPassword());

    assertNotNull(con);
    con.close();
  }
}
