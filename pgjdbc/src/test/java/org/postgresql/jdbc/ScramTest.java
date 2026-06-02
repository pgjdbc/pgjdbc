/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.NumberFormat;
import java.util.Properties;

class ScramTest {

  private static Connection con;
  private static final String ROLE_NAME = "testscram";

  @BeforeAll
  public static void setUp() throws Exception {
    con = TestUtil.openPrivilegedDB();
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v10));
  }

  @AfterAll
  public static void tearDown() throws Exception {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("DROP ROLE IF EXISTS " + ROLE_NAME);
    }
    TestUtil.closeDB(con);
  }

  /**
   * Test creating a role with passwords WITH spaces and opening a connection using the same
   * password, should work because is the "same" password.
   *
   * <p>https://github.com/pgjdbc/pgjdbc/issues/1970
   */
  @ParameterizedTest
  @ValueSource(strings = {"My Space", "$ec ret", " rover june spelling ",
      "!zj5hs*k5 STj@DaRUy", "q\u00A0w\u2000e\u2003r\u2009t\u3000y"})
  void testPasswordWithSpace(String passwd) throws SQLException {
    createRole(passwd); // Create role password with spaces.

    Properties props = new Properties();
    props.setProperty("username", ROLE_NAME);
    props.setProperty("password", passwd);

    try (Connection c = assertDoesNotThrow(() -> TestUtil.openDB(props));
        Statement stmt = c.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT current_user")) {
      assertTrue(rs.next());
      assertEquals(ROLE_NAME, rs.getString(1));
    }
  }

  /**
   * Test creating a role with passwords WITHOUT spaces and opening a connection using password with
   * spaces should fail since the spaces should not be stripped out.
   *
   * <p>https://github.com/pgjdbc/pgjdbc/issues/2000
   */
  @ParameterizedTest
  @ValueSource(strings = {"My Space", "$ec ret", "rover june spelling",
      "!zj5hs*k5 STj@DaRUy", "q\u00A0w\u2000e\u2003r\u2009t\u3000y"})
  void testPasswordWithoutSpace(String passwd) throws SQLException {
    String passwdNoSpaces = passwd.codePoints()
        .filter(i -> !Character.isSpaceChar(i))
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();

    createRole(passwdNoSpaces); // Create role password without spaces.

    Properties props = new Properties();
    props.setProperty("username", ROLE_NAME);
    props.setProperty("password", passwd); // Open connection with spaces

    SQLException ex = assertThrows(SQLException.class, () -> TestUtil.openDB(props));
    assertEquals(PSQLState.INVALID_PASSWORD.getState(), ex.getSQLState());
  }

  private PSQLException scramAuthExpectingFailure(String scramMaxIterations,
      int serverScramIterations, String password) throws SQLException {
    createRoleWithCustomScramIters(serverScramIterations);
    Properties props = scramProperties(password);
    if (scramMaxIterations != null) {
      PGProperty.SCRAM_MAX_ITERATIONS.set(props, scramMaxIterations);
    }
    return assertThrows(PSQLException.class,
        () -> DriverManager.getConnection(TestUtil.getURL(), props));
  }

  @Test
  void rejectIterationCountAboveDefaultCap() throws SQLException {
    int serverScramIterations = 789_123_456;
    PSQLException ex = scramAuthExpectingFailure(null, serverScramIterations, "does-not-matter");
    assertTrue(ex.getMessage().contains("exceeds"),
        "expected iteration-cap error, got: " + ex.getMessage());
    assertTrue(ex.getMessage().contains("scramMaxIterations"),
        "error should reference the connection property name, got: " + ex.getMessage());
    NumberFormat nf = NumberFormat.getNumberInstance();
    assertTrue(ex.getMessage().contains(nf.format(serverScramIterations)),
        "error should include the server-supplied iteration count, got: " + ex.getMessage());
  }

  @Test
  void rejectIterationCountAboveCustomCap() throws SQLException {
    int scramMaxIterations = 123_456;
    int serverScramIterations = 789_123_456;
    PSQLException ex = scramAuthExpectingFailure(Integer.toString(scramMaxIterations),
        serverScramIterations, "does-not-matter");
    NumberFormat nf = NumberFormat.getNumberInstance();
    assertTrue(ex.getMessage().contains(nf.format(scramMaxIterations)),
        "error should include the configured cap, got: " + ex.getMessage());
    assertTrue(ex.getMessage().contains(nf.format(serverScramIterations)),
        "error should include the server-supplied iteration count, got: " + ex.getMessage());
  }

  @Test
  void rejectValidCredentialsAboveCustomCap() throws SQLException {
    String password = "t0pSecret";
    createRole(password);
    Properties props = scramProperties(password);
    PGProperty.SCRAM_MAX_ITERATIONS.set(props, "1234");
    PSQLException ex = assertThrows(PSQLException.class,
        () -> DriverManager.getConnection(TestUtil.getURL(), props));
    NumberFormat nf = NumberFormat.getNumberInstance();
    assertTrue(ex.getMessage().contains(nf.format(1234)),
        "error should include the configured cap, got: " + ex.getMessage());
  }

  @Test
  void acceptsValidCredentialsBelowCustomCap() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v16));
    int serverScramIterations = Integer.parseInt(queryForString(con, "SHOW scram_iterations"));
    String password = "t0pSecret";
    createRole(password);
    Properties props = scramProperties(password);
    PGProperty.SCRAM_MAX_ITERATIONS.set(props, Integer.toString(serverScramIterations));
    try (Connection conn = DriverManager.getConnection(TestUtil.getURL(), props)) {
      String username = queryForString(conn, "SELECT USER");
      assertEquals(ROLE_NAME, username);
    }
  }

  private static String queryForString(Connection conn, String sql) throws SQLException {
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue(rs.next());
      return rs.getString(1);
    }
  }

  private Properties scramProperties(String password) {
    Properties props = new Properties();
    PGProperty.USER.set(props, ROLE_NAME);
    props.setProperty("username", ROLE_NAME);
    PGProperty.PASSWORD.set(props, password);
    return props;
  }

  private void createRole(String passwd) throws SQLException {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SET password_encryption='scram-sha-256'");
      stmt.execute("DROP ROLE IF EXISTS " + ROLE_NAME);
      stmt.execute("CREATE ROLE " + ROLE_NAME + " WITH LOGIN PASSWORD '" + passwd + "'");
    }
  }

  private void createRoleWithCustomScramIters(int iters) throws SQLException {
    TestUtil.execute("DROP ROLE IF EXISTS " + ROLE_NAME, con);
    TestUtil.execute("CREATE ROLE " + ROLE_NAME + " WITH LOGIN", con);
    // SCRAM-SHA-256$<iter>:<salt-base64>$<StoredKey-base64>:<ServerKey-base64>
    String encodedPassword = "SCRAM-SHA-256$" + iters
        + ":AAAAAAAAAAAAAAAAAAAAAA=="
        + "$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        + ":AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    TestUtil.execute("UPDATE pg_authid SET rolpassword = '" + encodedPassword
        + "' WHERE rolname = '" + ROLE_NAME + "'", con);
  }

}
