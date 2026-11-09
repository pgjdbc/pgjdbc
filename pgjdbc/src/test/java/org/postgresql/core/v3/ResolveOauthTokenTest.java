/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGProperty;
import org.postgresql.plugin.OauthTokenProvider;
import org.postgresql.plugin.OauthTokenRequest;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Properties;

class ResolveOauthTokenTest {

  /**
   * Records the request the driver builds, so the test can assert the connection details reach
   * the provider.
   */
  public static class RecordingProvider implements OauthTokenProvider {
    static @Nullable OauthTokenRequest lastRequest;

    @Override
    public char[] getToken(OauthTokenRequest request) {
      lastRequest = request;
      return "recorded-token".toCharArray();
    }
  }

  /** Returns an array of length zero, which the driver must refuse. */
  public static class EmptyTokenProvider implements OauthTokenProvider {
    @Override
    public char[] getToken(OauthTokenRequest request) {
      return new char[0];
    }
  }

  /** Returns no token at all, which the driver must refuse just as it refuses an empty one. */
  public static class NullTokenProvider implements OauthTokenProvider {
    @SuppressWarnings("return")
    @Override
    public char @Nullable [] getToken(OauthTokenRequest request) {
      return null;
    }
  }

  /** Fails with its own SQLSTATE, which the driver must pass to the caller unchanged. */
  public static class FailingProvider implements OauthTokenProvider {
    static final PSQLException FAILURE =
        new PSQLException("The identity provider is unavailable", PSQLState.CONNECTION_FAILURE);

    @Override
    public char[] getToken(OauthTokenRequest request) throws PSQLException {
      throw FAILURE;
    }
  }

  /** Fails with an unchecked exception, which the driver must wrap. */
  public static class BrokenProvider implements OauthTokenProvider {
    static final IllegalStateException FAILURE = new IllegalStateException("no credentials");

    @Override
    public char[] getToken(OauthTokenRequest request) {
      throw FAILURE;
    }
  }

  static final HostSpec HOST = new HostSpec("db.example.com", 5433);
  static final String USER = "alice";

  static char[] resolve(Properties info) throws PSQLException {
    return OauthAuthenticator.resolveOauthToken(HOST, USER, info);
  }

  private static Properties providerProps() {
    Properties info = new Properties();
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(info, RecordingProvider.class.getName());
    PGProperty.PG_DBNAME.set(info, "appdb");
    return info;
  }

  @Test
  void requestCarriesTheConnection() throws Exception {
    RecordingProvider.lastRequest = null;

    assertArrayEquals("recorded-token".toCharArray(),
        resolve(providerProps()));

    OauthTokenRequest request = RecordingProvider.lastRequest;
    assertNotNull(request, "The provider should have been called");
    assertEquals("db.example.com", request.getHost());
    assertEquals(5433, request.getPort());
    assertEquals(USER, request.getUser());
    assertEquals("appdb", request.getDatabase());
  }

  @Test
  void unsetPropertiesArriveAsNull() throws Exception {
    RecordingProvider.lastRequest = null;

    Properties info = new Properties();
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(info, RecordingProvider.class.getName());
    resolve(info);

    OauthTokenRequest request = RecordingProvider.lastRequest;
    assertNotNull(request, "The provider should have been called");
    assertEquals(null, request.getDatabase());
  }

  @Test
  void staticTokenWinsOverProvider() throws Exception {
    RecordingProvider.lastRequest = null;

    Properties info = providerProps();
    PGProperty.OAUTH_TOKEN.set(info, "static-token");

    assertArrayEquals("static-token".toCharArray(),
        resolve(info));
    assertEquals(null, RecordingProvider.lastRequest, "The provider must not be called");
  }

  @Test
  void providerThatCannotBeLoadedIsReported() {
    Properties info = new Properties();
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(info, "com.example.NoSuchTokenProvider");

    PSQLException ex = assertThrows(PSQLException.class,
        () -> resolve(info));

    assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), ex.getSQLState());
    assertEquals(GT.tr("Unable to load OAuth token provider {0}", "com.example.NoSuchTokenProvider"),
        ex.getMessage());
  }

  @Test
  void providerReturningAnEmptyTokenIsReported() {
    Properties info = new Properties();
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(info, EmptyTokenProvider.class.getName());

    PSQLException ex = assertThrows(PSQLException.class,
        () -> resolve(info));

    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(GT.tr("OAuth token provider {0} returned a null or empty token",
        EmptyTokenProvider.class.getName()), ex.getMessage());
  }

  @Test
  void providerReturningNoTokenIsReported() {
    Properties info = new Properties();
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(info, NullTokenProvider.class.getName());

    PSQLException ex = assertThrows(PSQLException.class,
        () -> resolve(info));

    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(GT.tr("OAuth token provider {0} returned a null or empty token",
        NullTokenProvider.class.getName()), ex.getMessage());
  }

  @Test
  void providerPSQLExceptionReachesTheCallerUnchanged() {
    Properties info = new Properties();
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(info, FailingProvider.class.getName());

    PSQLException ex = assertThrows(PSQLException.class,
        () -> resolve(info));

    assertSame(FailingProvider.FAILURE, ex);
  }

  @Test
  void providerFailureIsWrappedWithTheProviderName() {
    Properties info = new Properties();
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(info, BrokenProvider.class.getName());

    PSQLException ex = assertThrows(PSQLException.class,
        () -> resolve(info));

    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(GT.tr("OAuth token provider {0} failed to return a token",
        BrokenProvider.class.getName()), ex.getMessage());
    assertSame(BrokenProvider.FAILURE, ex.getCause());
  }
}
