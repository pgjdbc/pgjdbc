/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class SendRecvBufferSizeTest extends BaseTest4 {

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.SEND_BUFFER_SIZE.set(props, "1024");
    PGProperty.RECEIVE_BUFFER_SIZE.set(props, "1024");
  }

  @Before
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "hold", "a int");
    Statement stmt = con.createStatement();
    stmt.execute("INSERT INTO hold VALUES (1)");
    stmt.execute("INSERT INTO hold VALUES (2)");
    stmt.close();
  }

  @After
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "hold");
    super.tearDown();
  }

  // dummy test
  @Test
  public void testSelect() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("select * from hold");
    stmt.close();
  }
}
