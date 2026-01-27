/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.plugin.AuthenticationPlugin;
import org.postgresql.plugin.AuthenticationRequestType;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.function.Consumer;

class AuthenticationPluginTest {
  @BeforeAll
  static void setUp() throws SQLException {
    TestUtil.assumeHaveMinimumServerVersion(ServerVersion.v10);
  }

  public static class DummyAuthenticationPlugin implements AuthenticationPlugin {
    private static Consumer<AuthenticationRequestType> onGetPassword;

    @Override
    public @Nullable char[] getPassword(AuthenticationRequestType type) throws PSQLException {
      onGetPassword.accept(type);

      // Ex: "MD5" => "DUMMY-MD5"
      return ("DUMMY-" + type.toString()).toCharArray();
    }
  }

  private static void testAuthPlugin(String username, String passwordEncryption, AuthenticationRequestType expectedType) throws SQLException {
    createRole(username, passwordEncryption, "DUMMY-" + expectedType.toString());
    try {
      Properties props = new Properties();
      props.setProperty(PGProperty.AUTHENTICATION_PLUGIN_CLASS_NAME.getName(), DummyAuthenticationPlugin.class.getName());
      PGProperty.USER.set(props, username);

      boolean[] wasCalled = {false};
      DummyAuthenticationPlugin.onGetPassword = type -> {
        wasCalled[0] = true;
        assertEquals(expectedType, type, "The authentication type should match");
      };
      try (Connection conn = TestUtil.openDB(props)) {
        assertTrue(wasCalled[0], "The custom authentication plugin should be invoked");
      }
    } finally {
      dropRole(username);
    }
  }

  @Test
  void authPluginMD5() throws Exception {
    testAuthPlugin("auth_plugin_test_md5", "md5", AuthenticationRequestType.MD5_PASSWORD);
  }

  @Test
  void authPluginSASL() throws Exception {
    testAuthPlugin("auth_plugin_test_sasl", "scram-sha-256", AuthenticationRequestType.SASL);
  }

  private static void createRole(String username, String passwordEncryption, String password) throws SQLException {
    try (Connection conn = TestUtil.openPrivilegedDB()) {
      TestUtil.execute(conn, "SET password_encryption='" + passwordEncryption + "'");
      TestUtil.execute(conn, "DROP ROLE IF EXISTS " + username);
      TestUtil.execute(conn, "CREATE USER " + username + " WITH PASSWORD '" + password + "'");
    }
  }

  private static void dropRole(String username) throws SQLException {
    try (Connection conn = TestUtil.openPrivilegedDB()) {
      TestUtil.execute(conn, "DROP ROLE IF EXISTS " + username);
    }
  }
}
