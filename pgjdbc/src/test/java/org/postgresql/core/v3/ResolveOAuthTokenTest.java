/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGProperty;
import org.postgresql.plugin.OAuthTokenProvider;
import org.postgresql.plugin.OAuthTokenRequest;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Properties;

class ResolveOAuthTokenTest {

  /**
   * Records the request the driver builds, so the test can assert every connection property
   * reaches the provider.
   */
  public static class RecordingProvider implements OAuthTokenProvider {
    static @Nullable OAuthTokenRequest lastRequest;

    @Override
    public char[] getToken(OAuthTokenRequest request) {
      lastRequest = request;
      return "recorded-token".toCharArray();
    }
  }

  /** Returns an array of length zero, which the driver must refuse. */
  public static class EmptyTokenProvider implements OAuthTokenProvider {
    @Override
    public char[] getToken(OAuthTokenRequest request) {
      return new char[0];
    }
  }

  /** Returns no token at all, which the driver must refuse just as it refuses an empty one. */
  public static class NullTokenProvider implements OAuthTokenProvider {
    @SuppressWarnings("return")
    @Override
    public char @Nullable [] getToken(OAuthTokenRequest request) {
      return null;
    }
  }

  private static Properties providerProps() {
    Properties info = new Properties();
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(info, RecordingProvider.class.getName());
    PGProperty.OAUTH_ISSUER.set(info, "https://issuer.example.com");
    PGProperty.OAUTH_CLIENT_ID.set(info, "client-id");
    PGProperty.OAUTH_CLIENT_SECRET.set(info, "client-secret");
    PGProperty.OAUTH_SCOPE.set(info, "scope");
    return info;
  }

  @Test
  void requestCarriesEveryOAuthProperty() throws Exception {
    RecordingProvider.lastRequest = null;

    assertArrayEquals("recorded-token".toCharArray(),
        ConnectionFactoryImpl.resolveOAuthToken(providerProps()));

    OAuthTokenRequest request = RecordingProvider.lastRequest;
    assertNotNull(request, "The provider should have been called");
    assertEquals("https://issuer.example.com", request.getIssuer());
    assertEquals("client-id", request.getClientId());
    assertEquals("client-secret", request.getClientSecret());
    assertEquals("scope", request.getScope());
  }

  @Test
  void unsetPropertiesArriveAsNull() throws Exception {
    RecordingProvider.lastRequest = null;

    Properties info = new Properties();
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(info, RecordingProvider.class.getName());
    ConnectionFactoryImpl.resolveOAuthToken(info);

    OAuthTokenRequest request = RecordingProvider.lastRequest;
    assertNotNull(request, "The provider should have been called");
    assertEquals(null, request.getIssuer());
    assertEquals(null, request.getClientId());
    assertEquals(null, request.getClientSecret());
    assertEquals(null, request.getScope());
  }

  @Test
  void staticTokenWinsOverProvider() throws Exception {
    RecordingProvider.lastRequest = null;

    Properties info = providerProps();
    PGProperty.OAUTH_TOKEN.set(info, "static-token");

    assertArrayEquals("static-token".toCharArray(),
        ConnectionFactoryImpl.resolveOAuthToken(info));
    assertEquals(null, RecordingProvider.lastRequest, "The provider must not be called");
  }

  @Test
  void providerThatCannotBeLoadedIsReported() {
    Properties info = new Properties();
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(info, "com.example.NoSuchTokenProvider");

    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.resolveOAuthToken(info));

    assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), ex.getSQLState());
    assertEquals(GT.tr("Unable to load OAuth token provider {0}", "com.example.NoSuchTokenProvider"),
        ex.getMessage());
  }

  @Test
  void providerReturningAnEmptyTokenIsReported() {
    Properties info = new Properties();
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(info, EmptyTokenProvider.class.getName());

    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.resolveOAuthToken(info));

    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(GT.tr("OAuth token provider returned no token"), ex.getMessage());
  }

  @Test
  void providerReturningNoTokenIsReported() {
    Properties info = new Properties();
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(info, NullTokenProvider.class.getName());

    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.resolveOAuthToken(info));

    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(GT.tr("OAuth token provider returned no token"), ex.getMessage());
  }
}
