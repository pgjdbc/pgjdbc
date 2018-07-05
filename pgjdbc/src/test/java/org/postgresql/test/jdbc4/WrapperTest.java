/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.PGConnection;
import org.postgresql.PGStatement;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class WrapperTest {

  private Connection _conn;
  private Statement _statement;

  @Before
  public void setUp() throws Exception {
    _conn = TestUtil.openDB();
    _statement = _conn.prepareStatement("SELECT 1");
  }

  @After
  public void tearDown() throws SQLException {
    _statement.close();
    TestUtil.closeDB(_conn);
  }

  /**
   * This interface is private, and so cannot be supported by any wrapper.
   */
  private interface PrivateInterface {
  }

  @Test
  public void testConnectionIsWrapperForPrivate() throws SQLException {
    assertFalse(_conn.isWrapperFor(PrivateInterface.class));
  }

  @Test
  public void testConnectionIsWrapperForConnection() throws SQLException {
    assertTrue(_conn.isWrapperFor(Connection.class));
  }

  @Test
  public void testConnectionIsWrapperForPGConnection() throws SQLException {
    assertTrue(_conn.isWrapperFor(PGConnection.class));
  }

  @Test
  public void testConnectionUnwrapPrivate() throws SQLException {
    try {
      _conn.unwrap(PrivateInterface.class);
      fail("unwrap of non-wrapped interface should fail");
    } catch (SQLException e) {
    }
  }

  @Test
  public void testConnectionUnwrapConnection() throws SQLException {
    Object v = _conn.unwrap(Connection.class);
    assertNotNull(v);
    assertTrue(v instanceof Connection);
  }

  @Test
  public void testConnectionUnwrapPGConnection() throws SQLException {
    Object v = _conn.unwrap(PGConnection.class);
    assertNotNull(v);
    assertTrue(v instanceof PGConnection);
  }

  @Test
  public void testConnectionUnwrapPGDataSource() throws SQLException {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setDatabaseName(TestUtil.getDatabase());
    dataSource.setServerName(TestUtil.getServer());
    dataSource.setPortNumber(TestUtil.getPort());
    Connection connection = dataSource.getConnection(TestUtil.getUser(), TestUtil.getPassword());
    assertNotNull("Unable to obtain a connection from PGSimpleDataSource", connection);
    Object v = connection.unwrap(PGConnection.class);
    assertTrue("connection.unwrap(PGConnection.class) should return PGConnection instance"
            + ", actual instance is " + v,
        v instanceof PGConnection);
  }

  @Test
  public void testStatementIsWrapperForPrivate() throws SQLException {
    assertFalse(_statement.isWrapperFor(PrivateInterface.class));
  }

  @Test
  public void testStatementIsWrapperForStatement() throws SQLException {
    assertTrue(_statement.isWrapperFor(Statement.class));
  }

  @Test
  public void testStatementIsWrapperForPGStatement() throws SQLException {
    assertTrue(_statement.isWrapperFor(PGStatement.class));
  }

  @Test
  public void testStatementUnwrapPrivate() throws SQLException {
    try {
      _statement.unwrap(PrivateInterface.class);
      fail("unwrap of non-wrapped interface should fail");
    } catch (SQLException e) {
    }
  }

  @Test
  public void testStatementUnwrapStatement() throws SQLException {
    Object v = _statement.unwrap(Statement.class);
    assertNotNull(v);
    assertTrue(v instanceof Statement);
  }

  @Test
  public void testStatementUnwrapPGStatement() throws SQLException {
    Object v = _statement.unwrap(PGStatement.class);
    assertNotNull(v);
    assertTrue(v instanceof PGStatement);
  }

}
