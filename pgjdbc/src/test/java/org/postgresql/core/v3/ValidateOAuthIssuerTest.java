/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGProperty;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Properties;

class ValidateOAuthIssuerTest {

  private static String httpsRequiredMessage(String issuer) {
    return "OAuth issuer URL must use HTTPS: " + issuer
        + ". Set oauthAllowInsecureIssuer=true to disable this check for development.";
  }

  private static Properties propsWithIssuer(String issuer) {
    Properties info = new Properties();
    if (issuer != null) {
      PGProperty.OAUTH_ISSUER.set(info, issuer);
    }
    return info;
  }

  @Test
  void httpsIssuerPasses() {
    assertDoesNotThrow(
        () -> ConnectionFactoryImpl.validateOAuthIssuer(
            propsWithIssuer("https://issuer.example.com")));
  }

  @Test
  void httpsSchemeIsCaseInsensitive() {
    assertDoesNotThrow(
        () -> ConnectionFactoryImpl.validateOAuthIssuer(
            propsWithIssuer("HTTPS://issuer.example.com")));
  }

  @Test
  void emptyIssuerIsNoOp() {
    assertDoesNotThrow(
        () -> ConnectionFactoryImpl.validateOAuthIssuer(propsWithIssuer("")));
  }

  @Test
  void unsetIssuerIsNoOp() {
    assertDoesNotThrow(
        () -> ConnectionFactoryImpl.validateOAuthIssuer(propsWithIssuer(null)));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "http://issuer.example.com",
      "http://localhost:8080",
      "http://127.0.0.1/realms/test",
  })
  void httpIssuerRejected(String issuer) {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.validateOAuthIssuer(propsWithIssuer(issuer)));
    assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), ex.getSQLState());
    assertEquals(httpsRequiredMessage(issuer), ex.getMessage());
  }

  @Test
  void malformedIssuerRejected() {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.validateOAuthIssuer(propsWithIssuer("ht!tp://bad url")));
    assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), ex.getSQLState());
    // A malformed URL is not fixed by oauthAllowInsecureIssuer, so it must not be suggested.
    assertEquals("OAuth issuer is not a valid URL: ht!tp://bad url", ex.getMessage());
  }

  @Test
  void issuerWithoutSchemeRejectedAsNonHttps() {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.validateOAuthIssuer(propsWithIssuer("issuer.example.com")));
    assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), ex.getSQLState());
    assertEquals(httpsRequiredMessage("issuer.example.com"), ex.getMessage());
  }

  @Test
  void httpAcceptedWhenInsecureIssuerAllowed() {
    Properties info = propsWithIssuer("http://issuer.example.com");
    PGProperty.OAUTH_ALLOW_INSECURE_ISSUER.set(info, true);
    assertDoesNotThrow(() -> ConnectionFactoryImpl.validateOAuthIssuer(info));
  }

  @Test
  void staticTokenWithHttpIssuerRejected() {
    Properties info = propsWithIssuer("http://issuer.example.com");
    PGProperty.OAUTH_TOKEN.set(info, "static-token");
    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.resolveOAuthToken(info));
    assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), ex.getSQLState());
    assertEquals(httpsRequiredMessage("http://issuer.example.com"), ex.getMessage());
  }

  @Test
  void staticTokenWithHttpsIssuerAccepted() throws Exception {
    Properties info = propsWithIssuer("https://issuer.example.com");
    PGProperty.OAUTH_TOKEN.set(info, "static-token");
    assertArrayEquals("static-token".toCharArray(),
        ConnectionFactoryImpl.resolveOAuthToken(info));
  }
}
