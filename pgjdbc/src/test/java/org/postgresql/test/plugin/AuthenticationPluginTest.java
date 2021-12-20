/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.plugin;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.plugin.AuthenticationPlugin;
import org.postgresql.plugin.AuthenticationRequestType;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.function.Consumer;

public class AuthenticationPluginTest {
  @BeforeClass
  public static void setUp() throws SQLException {
    TestUtil.assumeHaveMinimumServerVersion(ServerVersion.v10);
  }

  public static class DummyAuthenticationPlugin implements AuthenticationPlugin {
    private static Consumer<AuthenticationRequestType> onGetPassword;

    @Override
    public @Nullable String getPassword(AuthenticationRequestType type) throws PSQLException {
      onGetPassword.accept(type);

      // Ex: "MD5" => "DUMMY-MD5"
      return "DUMMY-" + type.toString();
    }
  }

  private void testAuthPlugin(String username, String passwordEncryption, AuthenticationRequestType expectedType) throws SQLException {
    createRole(username, passwordEncryption, "DUMMY-" + expectedType.toString());
    try {
      Properties props = new Properties();
      props.setProperty(PGProperty.AUTHENTICATION_PLUGIN_CLASS_NAME.getName(), DummyAuthenticationPlugin.class.getName());
      props.setProperty("username", username);

      boolean[] wasCalled = { false };
      DummyAuthenticationPlugin.onGetPassword = type -> {
        wasCalled[0] = true;
        Assert.assertEquals("The authentication type should match", expectedType, type);
      };
      try (Connection conn = TestUtil.openDB(props)) {
        Assert.assertTrue("The custom authentication plugin should be invoked", wasCalled[0]);
      }
    } finally {
      dropRole(username);
    }
  }

  @Test
  public void testAuthPluginMD5() throws Exception {
    testAuthPlugin("auth_plugin_test_md5", "md5", AuthenticationRequestType.MD5_PASSWORD);
  }

  @Test
  public void testAuthPluginSASL() throws Exception {
    testAuthPlugin("auth_plugin_test_sasl", "scram-sha-256", AuthenticationRequestType.SASL);
  }

  private static void createRole(String username, String passwordEncryption, String password) throws SQLException {
    try (Connection conn = TestUtil.openPrivilegedDB()) {
      TestUtil.execute("SET password_encryption='" + passwordEncryption + "'", conn);
      TestUtil.execute("DROP ROLE IF EXISTS " + username, conn);
      TestUtil.execute("CREATE USER " + username + " WITH PASSWORD '" + password + "'", conn);
    }
  }

  private static void dropRole(String username) throws SQLException {
    try (Connection conn = TestUtil.openPrivilegedDB()) {
      TestUtil.execute("DROP ROLE IF EXISTS " + username, conn);
    }
  }
}
