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
import org.postgresql.test.annotations.EnabledForServerVersionRange;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.NumberFormat;
import java.util.Properties;
import java.util.stream.Stream;

class ScramTest {

  private static Connection con;
  private static final String ROLE_NAME = "testscram";

  @BeforeAll
  static void setUp() throws Exception {
    con = TestUtil.openPrivilegedDB();
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v10));
  }

  @AfterAll
  static void tearDown() throws Exception {
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
  void passwordWithSpace(String passwd) throws SQLException {
    createRole(passwd); // Create role password with spaces.

    Properties props = new Properties();
    PGProperty.USER.set(props, ROLE_NAME);
    PGProperty.PASSWORD.set(props, passwd);

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
  void passwordWithoutSpace(String passwd) throws SQLException {
    String passwdNoSpaces = passwd.codePoints()
        .filter(i -> !Character.isSpaceChar(i))
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();

    createRole(passwdNoSpaces); // Create role password without spaces.

    Properties props = new Properties();
    PGProperty.USER.set(props, ROLE_NAME);
    PGProperty.PASSWORD.set(props, passwd); // Open connection with spaces

    SQLException ex = assertThrows(SQLException.class, () -> TestUtil.openDB(props));
    assertEquals(PSQLState.INVALID_PASSWORD.getState(), ex.getSQLState());
  }

  private static Stream<Arguments> provideArgsForTestInvalid() {
    return Stream.of(
      Arguments.of(null, "The server requested SCRAM-based authentication, but no password was provided."),
      Arguments.of("", "The server requested SCRAM-based authentication, but the password is an empty string.")
    );
  }

  @ParameterizedTest
  @MethodSource("provideArgsForTestInvalid")
  void invalidPasswords(String password, String expectedMessage) throws SQLException {
    // We are testing invalid passwords so that correct one does not matter
    createRole("anything_goes_here");
    SQLException e = assertThrows(
        SQLException.class,
        () -> DriverManager.getConnection(TestUtil.getURL(), ROLE_NAME, password),
        "SCRAM connection attempt with invalid password should fail");

    // The driver localises messages via GT.tr, so compare against the translated form
    // rather than the English source to keep the test locale-independent.
    assertEquals(GT.tr(expectedMessage), e.getMessage());
  }

  private PSQLException scramAuthExpectingFailure(String scramMaxIterations, int serverScramIterations, String password) throws SQLException {
    createRoleWithCustomScramIters(serverScramIterations);
    Properties props = new Properties();
    PGProperty.USER.set(props, ROLE_NAME);
    PGProperty.PASSWORD.set(props, password);
    if (scramMaxIterations != null) {
      PGProperty.SCRAM_MAX_ITERATIONS.set(props, scramMaxIterations);
    }
    return assertThrows(PSQLException.class, () -> TestUtil.openDB(props));
  }

  @Test
  void rejectIterationCountAboveDefaultCap() throws SQLException {
    int serverScramIterations = 789_123_456;
    PSQLException ex = scramAuthExpectingFailure(null, serverScramIterations, "does-not-matter");
    // The iteration-cap message is the only SCRAM error that references the property name,
    // so checking for it pins the test to the right error path without depending on locale.
    assertTrue(ex.getMessage().contains("scramMaxIterations"),
        "expected iteration-cap error referencing the connection property name, got: " + ex.getMessage());
    // The message is formatted through MessageFormat, which applies locale-aware grouping
    // to integer arguments; format the expected numbers the same way.
    NumberFormat nf = NumberFormat.getNumberInstance();
    assertTrue(ex.getMessage().contains(nf.format(serverScramIterations)),
        "error should include the server-supplied iteration count, got: " + ex.getMessage());
  }

  @Test
  void rejectIterationCountAboveCustomCap() throws SQLException {
    int scramMaxIterations = 123_456;
    int serverScramIterations = 789_123_456;
    PSQLException ex = scramAuthExpectingFailure(Integer.toString(scramMaxIterations), serverScramIterations, "does-not-matter");
    // The message is formatted through MessageFormat, which applies locale-aware grouping
    // to integer arguments; format the expected numbers the same way.
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
    Properties props = new Properties();
    PGProperty.USER.set(props, ROLE_NAME);
    PGProperty.PASSWORD.set(props, password);
    PGProperty.SCRAM_MAX_ITERATIONS.set(props, "1234");
    PSQLException ex = assertThrows(PSQLException.class, () -> TestUtil.openDB(props));
    // The message is formatted through MessageFormat, which applies locale-aware grouping
    // to integer arguments; format the expected numbers the same way.
    NumberFormat nf = NumberFormat.getNumberInstance();
    assertTrue(ex.getMessage().contains(nf.format(1234)),
        "error should include the configured cap, got: " + ex.getMessage());
  }

  @Test
  @EnabledForServerVersionRange(gte = "16")
  void acceptsValidCredentialsBelowCustomCap() throws SQLException {
    int serverScramIterations = Integer.parseInt(TestUtil.queryForString(con, "SHOW scram_iterations"));
    String password = "t0pSecret";
    createRole(password);
    Properties props = new Properties();
    PGProperty.USER.set(props, ROLE_NAME);
    PGProperty.PASSWORD.set(props, password);
    PGProperty.SCRAM_MAX_ITERATIONS.set(props, Integer.toString(serverScramIterations));
    try (Connection conn = TestUtil.openDB(props)) {
      String username = TestUtil.queryForString(conn, "SELECT USER");
      assertEquals(ROLE_NAME, username);
    }
  }

  private static void createRole(String passwd) throws SQLException {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SET password_encryption='scram-sha-256'");
      stmt.execute("DROP ROLE IF EXISTS " + ROLE_NAME);
      stmt.execute("CREATE ROLE " + ROLE_NAME + " WITH LOGIN PASSWORD '" + passwd + "'");
    }
  }

  private static void createRoleWithCustomScramIters(int iters) throws SQLException {
    TestUtil.execute(con, "DROP ROLE IF EXISTS " + ROLE_NAME);
    TestUtil.execute(con, "CREATE ROLE " + ROLE_NAME + " WITH LOGIN");
    // SCRAM-SHA-256$<iter>:<salt-base64>$<StoredKey-base64>:<ServerKey-base64>
    // salt: 16 zero bytes, StoredKey and ServerKey: 32 zero bytes each.
    String encodedPassword = "SCRAM-SHA-256$" + iters
        + ":AAAAAAAAAAAAAAAAAAAAAA=="
        + "$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        + ":AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    // NOTE: We must directly update the system catalog to prevent the server from trying to
    // verify the password at creation time. Otherwise it will try to hash empty string with
    // our huge number of iterations to ensure the password is not an empty string.
    TestUtil.execute(con, "UPDATE pg_authid SET rolpassword = '" + encodedPassword + "' WHERE rolname = '" + ROLE_NAME + "'");
  }
}
