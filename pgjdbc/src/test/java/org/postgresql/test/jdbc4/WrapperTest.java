/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGConnection;
import org.postgresql.PGStatement;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

class WrapperTest {

  private Connection conn;
  private Statement statement;

  @BeforeEach
  void setUp() throws Exception {
    conn = TestUtil.openDB();
    statement = conn.prepareStatement("SELECT 1");
  }

  @AfterEach
  void tearDown() throws SQLException {
    statement.close();
    TestUtil.closeDB(conn);
  }

  /**
   * This interface is private, and so cannot be supported by any wrapper.
   */
  private interface PrivateInterface {
  }

  @Test
  void connectionIsWrapperForPrivate() throws SQLException {
    assertFalse(conn.isWrapperFor(PrivateInterface.class));
  }

  @Test
  void connectionIsWrapperForConnection() throws SQLException {
    assertTrue(conn.isWrapperFor(Connection.class));
  }

  @Test
  void connectionIsWrapperForPGConnection() throws SQLException {
    assertTrue(conn.isWrapperFor(PGConnection.class));
  }

  @Test
  void connectionUnwrapPrivate() throws SQLException {
    try {
      conn.unwrap(PrivateInterface.class);
      fail("unwrap of non-wrapped interface should fail");
    } catch (SQLException e) {
    }
  }

  @Test
  void connectionUnwrapConnection() throws SQLException {
    Object v = conn.unwrap(Connection.class);
    assertNotNull(v);
    assertTrue(v instanceof Connection, "connection.unwrap(PGConnection.class) should return PGConnection instance"
        + ", actual instance is " + v);
  }

  @Test
  void connectionUnwrapPGConnection() throws SQLException {
    Object v = conn.unwrap(PGConnection.class);
    assertNotNull(v);
    assertTrue(v instanceof PGConnection, "connection.unwrap(PGConnection.class) should return PGConnection instance"
        + ", actual instance is " + v);
  }

  @Test
  void connectionUnwrapPGDataSource() throws SQLException {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setDatabaseName(TestUtil.getDatabase());
    dataSource.setServerName(TestUtil.getServer());
    dataSource.setPortNumber(TestUtil.getPort());
    Connection connection = dataSource.getConnection(TestUtil.getUser(), TestUtil.getPassword());
    assertNotNull(connection, "Unable to obtain a connection from PGSimpleDataSource");
    Object v = connection.unwrap(PGConnection.class);
    assertTrue(v instanceof PGConnection,
        "connection.unwrap(PGConnection.class) should return PGConnection instance"
            + ", actual instance is " + v);
  }

  @Test
  void statementIsWrapperForPrivate() throws SQLException {
    assertFalse(statement.isWrapperFor(PrivateInterface.class), "Should not be a wrapper for PrivateInterface");
  }

  @Test
  void statementIsWrapperForStatement() throws SQLException {
    assertTrue(statement.isWrapperFor(Statement.class), "Should be a wrapper for Statement");
  }

  @Test
  void statementIsWrapperForPGStatement() throws SQLException {
    assertTrue(statement.isWrapperFor(PGStatement.class), "Should be a wrapper for PGStatement");
  }

  @Test
  void statementUnwrapPrivate() throws SQLException {
    try {
      statement.unwrap(PrivateInterface.class);
      fail("unwrap of non-wrapped interface should fail");
    } catch (SQLException e) {
    }
  }

  @Test
  void statementUnwrapStatement() throws SQLException {
    Object v = statement.unwrap(Statement.class);
    assertNotNull(v);
    assertTrue(v instanceof Statement, "Should be instance of Statement, actual instance of " + v);
  }

  @Test
  void statementUnwrapPGStatement() throws SQLException {
    Object v = statement.unwrap(PGStatement.class);
    assertNotNull(v);
    assertTrue(v instanceof PGStatement, "Should be instance of PGStatement, actual instance of " + v);
  }

}
