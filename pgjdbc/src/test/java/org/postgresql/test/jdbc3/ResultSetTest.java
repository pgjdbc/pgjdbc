/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class ResultSetTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    conn = TestUtil.openDB();
    Statement stmt = conn.createStatement();
    stmt.execute("CREATE TEMP TABLE hold(a int)");
    stmt.execute("INSERT INTO hold VALUES (1)");
    stmt.execute("INSERT INTO hold VALUES (2)");
    stmt.close();
  }

  @After
  public void tearDown() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.execute("DROP TABLE hold");
    stmt.close();
    TestUtil.closeDB(conn);
  }

  @Test
  public void testHoldableResultSet() throws SQLException {
    Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);

    conn.setAutoCommit(false);
    stmt.setFetchSize(1);

    ResultSet rs = stmt.executeQuery("SELECT a FROM hold ORDER BY a");

    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));

    conn.commit();

    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertTrue(!rs.next());

    rs.close();
    stmt.close();
  }

}
