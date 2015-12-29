package org.postgresql.test.jdbc4.jdbc41;

import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class CloseOnCompletionTest extends TestCase {

  private Connection _conn;

  public CloseOnCompletionTest(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    _conn = TestUtil.openDB();
    TestUtil.createTable(_conn, "table1", "id integer");
  }

  protected void tearDown() throws SQLException {
    TestUtil.dropTable(_conn, "table1");
    TestUtil.closeDB(_conn);
  }

  /**
   * Test that the statement is not automatically closed if we do not ask for it
   */
  public void testWithoutCloseOnCompletion() throws SQLException {
    Statement stmt = _conn.createStatement();

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "*"));
    rs.close();
    assertFalse(stmt.isClosed());
  }

  /**
   * Test the behavior of closeOnCompletion with a single result set
   */
  public void testSingleResultSet() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.closeOnCompletion();

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "*"));
    rs.close();
    assertTrue(stmt.isClosed());
  }

  /**
   * Test the behavior of closeOnCompletion with a multiple result sets
   */
  public void testMultipleResultSet() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.closeOnCompletion();

    stmt.execute(TestUtil.selectSQL("table1", "*") + ";" +
        TestUtil.selectSQL("table1", "*") + ";");
    ResultSet rs = stmt.getResultSet();
    rs.close();
    assertFalse(stmt.isClosed());
    stmt.getMoreResults();
    rs = stmt.getResultSet();
    rs.close();
    assertTrue(stmt.isClosed());
  }

  /**
   * Test that when execution does not produce any result sets, closeOnCompletion has no effect
   * (spec)
   */
  public void testNoResultSet() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.closeOnCompletion();

    stmt.executeUpdate(TestUtil.insertSQL("table1", "1"));
    assertFalse(stmt.isClosed());
  }
}
