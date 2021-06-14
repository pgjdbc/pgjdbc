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

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

  private void createRole(String passwd) throws SQLException {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SET password_encryption='scram-sha-256'");
      stmt.execute("DROP ROLE IF EXISTS " + ROLE_NAME);
      stmt.execute("CREATE ROLE " + ROLE_NAME + " WITH LOGIN PASSWORD '" + passwd + "'");
    }
  }

}
