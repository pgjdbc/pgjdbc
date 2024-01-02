/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.socketfactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Properties;

class SocketFactoryTestSuite {

  private static final String STRING_ARGUMENT = "name of a socket";

  private Connection conn;

  @BeforeEach
  void setUp() throws Exception {
    Properties properties = new Properties();
    properties.put(PGProperty.SOCKET_FACTORY.getName(), CustomSocketFactory.class.getName());
    properties.put(PGProperty.SOCKET_FACTORY_ARG.getName(), STRING_ARGUMENT);
    conn = TestUtil.openDB(properties);
  }

  @AfterEach
  void tearDown() throws Exception {
    TestUtil.closeDB(conn);
  }

  /**
   * Test custom socket factory.
   */
  @Test
  void databaseMetaData() throws Exception {
    assertNotNull(CustomSocketFactory.getInstance(), "Custom socket factory not null");
    assertEquals(STRING_ARGUMENT, CustomSocketFactory.getInstance().getArgument());
    assertEquals(1, CustomSocketFactory.getInstance().getSocketCreated());
  }

}
