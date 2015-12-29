/*-------------------------------------------------------------------------
*
* Copyright (c) 2007-2013, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc4;

import org.postgresql.PGConnection;
import org.postgresql.PGStatement;
import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class WrapperTest extends TestCase {

  private Connection _conn;
  private Statement _statement;

  public WrapperTest(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    _conn = TestUtil.openDB();
    _statement = _conn.prepareStatement("SELECT 1");
  }

  protected void tearDown() throws SQLException {
    _statement.close();
    TestUtil.closeDB(_conn);
  }

  /**
   * This interface is private, and so cannot be supported by any wrapper
   */
  private static interface PrivateInterface {
  }

  ;

  public void testConnectionIsWrapperForPrivate() throws SQLException {
    assertFalse(_conn.isWrapperFor(PrivateInterface.class));
  }

  public void testConnectionIsWrapperForConnection() throws SQLException {
    assertTrue(_conn.isWrapperFor(Connection.class));
  }

  public void testConnectionIsWrapperForPGConnection() throws SQLException {
    assertTrue(_conn.isWrapperFor(PGConnection.class));
  }

  public void testConnectionUnwrapPrivate() throws SQLException {
    try {
      _conn.unwrap(PrivateInterface.class);
      fail("unwrap of non-wrapped interface should fail");
    } catch (SQLException e) {
    }
  }

  public void testConnectionUnwrapConnection() throws SQLException {
    Object v = _conn.unwrap(Connection.class);
    assertNotNull(v);
    assertTrue(v instanceof Connection);
  }

  public void testConnectionUnwrapPGConnection() throws SQLException {
    Object v = _conn.unwrap(PGConnection.class);
    assertNotNull(v);
    assertTrue(v instanceof PGConnection);
  }

  public void testStatementIsWrapperForPrivate() throws SQLException {
    assertFalse(_statement.isWrapperFor(PrivateInterface.class));
  }

  public void testStatementIsWrapperForStatement() throws SQLException {
    assertTrue(_statement.isWrapperFor(Statement.class));
  }

  public void testStatementIsWrapperForPGStatement() throws SQLException {
    assertTrue(_statement.isWrapperFor(PGStatement.class));
  }

  public void testStatementUnwrapPrivate() throws SQLException {
    try {
      _statement.unwrap(PrivateInterface.class);
      fail("unwrap of non-wrapped interface should fail");
    } catch (SQLException e) {
    }
  }

  public void testStatementUnwrapStatement() throws SQLException {
    Object v = _statement.unwrap(Statement.class);
    assertNotNull(v);
    assertTrue(v instanceof Statement);
  }

  public void testStatementUnwrapPGStatement() throws SQLException {
    Object v = _statement.unwrap(PGStatement.class);
    assertNotNull(v);
    assertTrue(v instanceof PGStatement);
  }

}
