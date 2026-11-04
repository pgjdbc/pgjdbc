/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGProperty;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Properties;

/**
 * With sslmode unset, the ssl property means verify-full when it is empty or true, and prefer when
 * it is false or unset. Any other value is refused.
 */
class SslModeTest {
  private static Properties ssl(String value) {
    Properties info = new Properties();
    PGProperty.SSL.set(info, value);
    return info;
  }

  @Test
  void unsetSslIsPrefer() throws PSQLException {
    assertEquals(SslMode.PREFER, SslMode.of(new Properties()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"false", "FALSE"})
  void falseIsPrefer(String value) throws PSQLException {
    assertEquals(SslMode.PREFER, SslMode.of(ssl(value)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "true", "TRUE"})
  void emptyOrTrueIsVerifyFull(String value) throws PSQLException {
    assertEquals(SslMode.VERIFY_FULL, SslMode.of(ssl(value)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "on", "yes", "verify-full", "true ", "0", "off"})
  void otherValuesAreRefused(String value) {
    PSQLException e = assertThrows(PSQLException.class, () -> SslMode.of(ssl(value)));
    assertEquals(PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState(), e.getSQLState());
  }

  @Test
  void sslmodeTakesPrecedence() throws PSQLException {
    Properties info = ssl("1");
    PGProperty.SSL_MODE.set(info, "require");
    assertEquals(SslMode.REQUIRE, SslMode.of(info));
  }
}
