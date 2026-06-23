/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.postgresql.core.SocketFactoryFactory;
import org.postgresql.jdbc.SslMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.ObjectFactory;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.opentest4j.MultipleFailuresError;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

import javax.net.SocketFactory;

class ObjectFactoryTest {
  Properties props = new Properties();

  static class BadObject {
    static boolean wasInstantiated;

    BadObject() {
      wasInstantiated = true;
      throw new RuntimeException("I should not be instantiated");
    }
  }

  private void testInvalidInstantiation(PGProperty prop, PSQLState expectedSqlState) {
    prop.set(props, BadObject.class.getName());

    BadObject.wasInstantiated = false;
    SQLException ex;
    try (Connection con = TestUtil.openDB(props)) {
      // BadObject always throws from its constructor, so a successful connection means the driver
      // never instantiated it. authenticationPluginClassName is loaded lazily and is skipped
      // entirely under "trust" authentication (see AuthenticationPluginManager.withPassword), so
      // this test can only run when the server requests a password from the test user.
      assumeFalse(prop == PGProperty.AUTHENTICATION_PLUGIN_CLASS_NAME,
          "The test database authenticated the test user via \"trust\", so "
              + "authenticationPluginClassName was never instantiated and this test cannot run. "
              + "Configure pg_hba.conf to request a password (md5/scram-sha-256) for the test user; "
              + "see the authentication tests section in TESTING.md.");
      throw new AssertionError(
          "Opening a connection should have failed because " + prop.getName()
              + " points to a class that cannot be instantiated, but it succeeded.");
    } catch (SQLException e) {
      ex = e;
    }

    try {
      assertAll(
          () -> assertFalse(BadObject.wasInstantiated, "ObjectFactory should not have "
              + "instantiated bad object for " + prop),
          () -> assertEquals(expectedSqlState.getState(), ex.getSQLState(), () -> "#getSQLState()"),
          () -> {
            assertThrows(
                ClassCastException.class,
                () -> {
                  throw ex.getCause();
                },
                () -> "Wrong class specified for " + prop.name()
                    + " => ClassCastException is expected in SQLException#getCause()"
            );
          }
      );
    } catch (MultipleFailuresError e) {
      // Add the original exception so it is easier to understand the reason for the test to fail
      e.addSuppressed(ex);
      throw e;
    }
  }

  @Test
  void invalidSocketFactory() {
    testInvalidInstantiation(PGProperty.SOCKET_FACTORY, PSQLState.CONNECTION_FAILURE);
  }

  @Test
  void invalidSSLFactory() {
    TestUtil.assumeSslTestsEnabled();
    // We need at least "require" to trigger SslSockerFactory instantiation
    PGProperty.SSL_MODE.set(props, SslMode.REQUIRE.value);
    testInvalidInstantiation(PGProperty.SSL_FACTORY, PSQLState.CONNECTION_FAILURE);
  }

  @Test
  void invalidAuthenticationPlugin() {
    testInvalidInstantiation(PGProperty.AUTHENTICATION_PLUGIN_CLASS_NAME,
        PSQLState.INVALID_PARAMETER_VALUE);
  }

  @Test
  void invalidSslHostnameVerifier() {
    TestUtil.assumeSslTestsEnabled();
    // Hostname verification is done at verify-full level only
    PGProperty.SSL_MODE.set(props, SslMode.VERIFY_FULL.value);
    PGProperty.SSL_ROOT_CERT.set(props, TestUtil.getSslTestCertPath("goodroot.crt"));
    testInvalidInstantiation(PGProperty.SSL_HOSTNAME_VERIFIER, PSQLState.CONNECTION_FAILURE);
  }

  @Test
  void socketFactoryReceivesCustomProperties() throws SQLException {
    Properties props = new Properties();
    PGProperty.SOCKET_FACTORY.set(props, CapturingSocketFactory.class.getName());
    props.setProperty("x-acme-customfield", "from-properties");

    SocketFactory socketFactory = SocketFactoryFactory.getSocketFactory(props);

    CapturingSocketFactory capturingSocketFactory =
        assertInstanceOf(CapturingSocketFactory.class, socketFactory);
    assertSame(props, capturingSocketFactory.properties);
    assertEquals("from-properties",
        capturingSocketFactory.properties.getProperty("x-acme-customfield"));
  }

  @Test
  void socketFactoryReceivesCustomPropertiesFromUrl() throws SQLException {
    Properties props = Driver.parseURL(
        "jdbc:postgresql://localhost/test?socketFactory="
            + CapturingSocketFactory.class.getName()
            + "&x-acme-customfield=from-url",
        null);
    assertNotNull(props);

    SocketFactory socketFactory = SocketFactoryFactory.getSocketFactory(props);

    CapturingSocketFactory capturingSocketFactory =
        assertInstanceOf(CapturingSocketFactory.class, socketFactory);
    assertSame(props, capturingSocketFactory.properties);
    assertEquals("from-url",
        capturingSocketFactory.properties.getProperty("x-acme-customfield"));
  }

  @Test
  void socketFactoryReceivesCustomPropertiesOnConnection() throws SQLException {
    CapturingSocketFactory.reset();
    Properties props = new Properties();
    PGProperty.SOCKET_FACTORY.set(props, CapturingSocketFactory.class.getName());
    props.setProperty("x-acme-customfield", "from-properties-connection");

    try (Connection connection = TestUtil.openDB(props)) {
      assertNotNull(connection);
    }

    Properties socketFactoryProperties = CapturingSocketFactory.getLastProperties();
    assertNotNull(socketFactoryProperties);
    assertEquals("from-properties-connection",
        socketFactoryProperties.getProperty("x-acme-customfield"));
  }

  @Test
  void socketFactoryReceivesCustomUrlPropertiesOnConnection() throws SQLException {
    CapturingSocketFactory.reset();
    Properties props = new Properties();
    TestUtil.setTestUrlProperty(props, PGProperty.SOCKET_FACTORY,
        CapturingSocketFactory.class.getName());
    props.setProperty(TestUtil.TEST_URL_PROPERTY_PREFIX + "x-acme-customfield",
        "from-url-connection");

    try (Connection connection = TestUtil.openDB(props)) {
      assertNotNull(connection);
    }

    Properties socketFactoryProperties = CapturingSocketFactory.getLastProperties();
    assertNotNull(socketFactoryProperties);
    assertEquals("from-url-connection",
        socketFactoryProperties.getProperty("x-acme-customfield"));
  }

  @Test
  void instantiateInvalidSocketFactory() {
    Properties props = new Properties();
    assertThrows(ClassCastException.class, () -> {
      ObjectFactory.instantiate(SocketFactory.class, BadObject.class.getName(), props,
          false, null);
    });
  }
}
