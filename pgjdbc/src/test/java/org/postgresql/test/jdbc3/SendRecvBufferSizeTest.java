/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class SendRecvBufferSizeTest {

  private Connection _conn;

  @Before
  public void setUp() throws Exception {
    System.setProperty("sendBufferSize", "1024");
    System.setProperty("receiveBufferSize", "1024");

    _conn = TestUtil.openDB();
    Statement stmt = _conn.createStatement();
    stmt.execute("CREATE TEMP TABLE hold(a int)");
    stmt.execute("INSERT INTO hold VALUES (1)");
    stmt.execute("INSERT INTO hold VALUES (2)");
    stmt.close();
  }

  @After
  public void tearDown() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.execute("DROP TABLE hold");
    stmt.close();
    TestUtil.closeDB(_conn);
  }


  // dummy test
  @Test
  public void testSelect() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.execute("select * from hold");
    stmt.close();
  }

}
