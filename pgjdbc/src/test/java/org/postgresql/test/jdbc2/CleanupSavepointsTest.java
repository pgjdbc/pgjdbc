/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Properties;

public class CleanupSavepointsTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "rollbacktest", "a int, str text");
    con.setAutoCommit(false);
  }

  @Override
  public void tearDown() throws SQLException {
    try {
      con.setAutoCommit(true);
      TestUtil.dropTable(con, "rollbacktest");
    } catch (Exception e) {
      e.printStackTrace();
    }
    super.tearDown();
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.AUTOSAVE.set(props, "always");
    PGProperty.CLEANUP_SAVEPOINTS.set(props, "true");
  }

  @Test
  public void testCommit() throws Exception {
    Statement stmt = con.createStatement();
    stmt.execute("INSERT INTO rollbacktest(a, str) VALUES (0, 'test')");

    con.commit();

    ResultSet rs = stmt.executeQuery("SELECT * FROM rollbacktest");

    assertTrue(rs.next());
    assertEquals(0, rs.getInt(1));
    assertEquals("test", rs.getString(2));

    stmt.close();
  }

  @Test
  public void testRollback() throws Exception {
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO rollbacktest(a, str) VALUES (0, 'test')");

    con.rollback();

    ResultSet rs = stmt.executeQuery("SELECT * FROM rollbacktest");

    assertTrue(!rs.next());

    stmt.close();
  }

  @Test
  public void testRollbackToSavepoint() throws Exception {
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO rollbacktest(a, str) VALUES (0, 'testbefore')");

    Savepoint savepoint = con.setSavepoint();

    stmt.executeUpdate("INSERT INTO rollbacktest(a, str) VALUES (1, 'testafter')");

    con.rollback(savepoint);

    ResultSet rs = stmt.executeQuery("SELECT * FROM rollbacktest");

    assertTrue(rs.next());
    assertEquals(0, rs.getInt(1));
    assertEquals("testbefore", rs.getString(2));

    stmt.close();
  }

  @Test
  public void testReleaseSavepoint() throws Exception {
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO rollbacktest(a, str) VALUES (0, 'testbefore')");

    Savepoint savepoint = con.setSavepoint();

    stmt.executeUpdate("INSERT INTO rollbacktest(a, str) VALUES (1, 'testafter')");

    con.releaseSavepoint(savepoint);

    ResultSet rs = stmt.executeQuery("SELECT * FROM rollbacktest ORDER BY a");

    assertTrue(rs.next());
    assertEquals(0, rs.getInt(1));
    assertEquals("testbefore", rs.getString(2));

    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertEquals("testafter", rs.getString(2));

    stmt.close();
  }
}
