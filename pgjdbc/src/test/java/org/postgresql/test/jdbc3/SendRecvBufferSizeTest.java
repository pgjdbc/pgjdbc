/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
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

  @BeforeAll
  static void createTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createTable(con, "hold", "a int");
    }
  }

  @AfterAll
  static void dropTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, "hold");
    }
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.execute(con, "TRUNCATE hold");
    TestUtil.execute(con, "INSERT INTO hold VALUES (1),(2)");
  }

  // dummy test
  @Test
  public void testSelect() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("select * from hold");
    stmt.close();
  }
}
