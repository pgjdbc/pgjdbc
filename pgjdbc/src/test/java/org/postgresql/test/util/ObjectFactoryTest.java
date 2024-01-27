/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.SslMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.ObjectFactory;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.opentest4j.MultipleFailuresError;

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
    SQLException ex = assertThrows(SQLException.class, () -> {
      TestUtil.openDB(props);
    });

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
  void instantiateInvalidSocketFactory() {
    Properties props = new Properties();
    assertThrows(ClassCastException.class, () -> {
      ObjectFactory.instantiate(SocketFactory.class, BadObject.class.getName(), props,
          false, null);
    });
  }
}
