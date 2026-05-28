/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGProperty;
import org.postgresql.core.AuthMethod;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;

class SelectSaslMechanismTest {

  private static final List<String> SCRAM_ONLY =
      Arrays.asList("SCRAM-SHA-256", "SCRAM-SHA-256-PLUS");
  private static final List<String> OAUTH_ONLY =
      Collections.singletonList(OAuthAuthenticator.SASL_MECHANISM);
  private static final List<String> BOTH =
      Arrays.asList(OAuthAuthenticator.SASL_MECHANISM, "SCRAM-SHA-256", "SCRAM-SHA-256-PLUS");

  private static final String REQUIRE_AUTH_MESSAGE =
      GT.tr("Authentication method is not allowed by requireAuth");

  private static final String OAUTH_CHANNEL_BINDING_MESSAGE =
      GT.tr("Channel binding is not supported for OAuth authentication.");

  private static final String OAUTH_NOT_CONFIGURED_MESSAGE =
      GT.tr("The server requested OAuth authentication, but neither {0} nor {1} is configured.",
          PGProperty.OAUTH_TOKEN.getName(),
          PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.getName());

  private static String unsupportedMessage(List<String> mechanisms) {
    return GT.tr("The server requested SASL authentication with mechanisms {0}, "
        + "but the driver supports none of them.", mechanisms);
  }

  private static Properties withToken() {
    Properties info = new Properties();
    PGProperty.OAUTH_TOKEN.set(info, "token");
    return info;
  }

  private static Properties withTokenProvider() {
    Properties info = new Properties();
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(info, "com.example.Provider");
    return info;
  }

  private static Properties noOAuth() {
    return new Properties();
  }

  private static EnumSet<AuthMethod> requireAuth(String value) throws PSQLException {
    EnumSet<AuthMethod> methods = AuthMethod.parseRequireAuth(value);
    assertNotNull(methods, "requireAuth=" + value + " parsed to no restriction");
    return methods;
  }

  @Test
  void oauthWinsWhenOfferedAndConfigured() throws Exception {
    assertEquals(AuthMethod.OAUTH,
        ConnectionFactoryImpl.selectSaslMechanism(BOTH, withToken(), null, ChannelBinding.PREFER));
    assertEquals(AuthMethod.OAUTH,
        ConnectionFactoryImpl.selectSaslMechanism(OAUTH_ONLY, withToken(), null, ChannelBinding.PREFER));
  }

  @Test
  void tokenProviderCountsAsConfiguredOAuth() throws Exception {
    assertEquals(AuthMethod.OAUTH,
        ConnectionFactoryImpl.selectSaslMechanism(BOTH, withTokenProvider(), null, ChannelBinding.PREFER));
  }

  @Test
  void unconfiguredOAuthFallsBackToScram() throws Exception {
    assertEquals(AuthMethod.SCRAM_SHA_256,
        ConnectionFactoryImpl.selectSaslMechanism(BOTH, noOAuth(), null, ChannelBinding.PREFER));
  }

  @Test
  void scramChosenWhenOnlyScramOffered() throws Exception {
    assertEquals(AuthMethod.SCRAM_SHA_256,
        ConnectionFactoryImpl.selectSaslMechanism(SCRAM_ONLY, withToken(), null, ChannelBinding.PREFER));
  }

  @Test
  void requireAuthExcludingOAuthFallsBackToScram() throws Exception {
    assertEquals(AuthMethod.SCRAM_SHA_256,
        ConnectionFactoryImpl.selectSaslMechanism(BOTH, withToken(), requireAuth("!oauth"), ChannelBinding.PREFER));
    assertEquals(AuthMethod.SCRAM_SHA_256,
        ConnectionFactoryImpl.selectSaslMechanism(BOTH, withToken(), requireAuth("scram-sha-256"), ChannelBinding.PREFER));
  }

  @Test
  void requireAuthExcludingScramStillAllowsOAuth() throws Exception {
    assertEquals(AuthMethod.OAUTH,
        ConnectionFactoryImpl.selectSaslMechanism(BOTH, withToken(),
            requireAuth("!scram-sha-256"), ChannelBinding.PREFER));
    assertEquals(AuthMethod.OAUTH,
        ConnectionFactoryImpl.selectSaslMechanism(BOTH, withToken(), requireAuth("oauth"), ChannelBinding.PREFER));
  }

  @Test
  void oauthOnlyServerRejectedWhenRequireAuthForbidsOAuth() {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.selectSaslMechanism(OAUTH_ONLY, withToken(),
            requireAuth("!oauth"), ChannelBinding.PREFER));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(REQUIRE_AUTH_MESSAGE, ex.getMessage());
  }

  @Test
  void scramOnlyServerRejectedWhenRequireAuthForbidsScram() {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.selectSaslMechanism(SCRAM_ONLY, withToken(),
            requireAuth("oauth"), ChannelBinding.PREFER));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(REQUIRE_AUTH_MESSAGE, ex.getMessage());
  }

  @Test
  void bothForbiddenByRequireAuthReportsRequireAuth() {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.selectSaslMechanism(BOTH, withToken(), requireAuth("md5"), ChannelBinding.PREFER));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(REQUIRE_AUTH_MESSAGE, ex.getMessage());
  }

  @Test
  void oauthOnlyServerWithoutOAuthConfigurationReportsMissingConfiguration() {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.selectSaslMechanism(OAUTH_ONLY, noOAuth(), null, ChannelBinding.PREFER));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(OAUTH_NOT_CONFIGURED_MESSAGE, ex.getMessage());
  }

  @Test
  void requireAuthOnlyAllowingOAuthWithoutOAuthConfigurationReportsMissingConfiguration()
      throws Exception {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.selectSaslMechanism(BOTH, noOAuth(), requireAuth("oauth"), ChannelBinding.PREFER));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(OAUTH_NOT_CONFIGURED_MESSAGE, ex.getMessage());
  }

  @Test
  void channelBindingRequireFallsBackToScram() throws Exception {
    assertEquals(AuthMethod.SCRAM_SHA_256,
        ConnectionFactoryImpl.selectSaslMechanism(BOTH, withToken(), null,
            ChannelBinding.REQUIRE));
    assertEquals(AuthMethod.SCRAM_SHA_256,
        ConnectionFactoryImpl.selectSaslMechanism(BOTH, withToken(), requireAuth("!md5"),
            ChannelBinding.REQUIRE));
  }

  @Test
  void channelBindingDisableAndPreferStillChooseOAuth() throws Exception {
    assertEquals(AuthMethod.OAUTH,
        ConnectionFactoryImpl.selectSaslMechanism(BOTH, withToken(), null,
            ChannelBinding.DISABLE));
    assertEquals(AuthMethod.OAUTH,
        ConnectionFactoryImpl.selectSaslMechanism(BOTH, withToken(), null,
            ChannelBinding.PREFER));
  }

  @Test
  void oauthOnlyServerRejectedWhenChannelBindingRequired() {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.selectSaslMechanism(OAUTH_ONLY, withToken(), null,
            ChannelBinding.REQUIRE));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(OAUTH_CHANNEL_BINDING_MESSAGE, ex.getMessage());
  }

  @Test
  void channelBindingRequiredWithScramForbiddenReportsChannelBinding() throws Exception {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.selectSaslMechanism(BOTH, withToken(), requireAuth("oauth"),
            ChannelBinding.REQUIRE));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(OAUTH_CHANNEL_BINDING_MESSAGE, ex.getMessage());
  }

  @Test
  void unconfiguredOAuthUnderChannelBindingRequireReportsMissingConfiguration() {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.selectSaslMechanism(OAUTH_ONLY, noOAuth(), null,
            ChannelBinding.REQUIRE));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(OAUTH_NOT_CONFIGURED_MESSAGE, ex.getMessage());
  }

  @Test
  void unknownMechanismReportsUnsupportedMechanism() {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.selectSaslMechanism(Collections.singletonList("PLAIN"),
            withToken(), null, ChannelBinding.PREFER));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(unsupportedMessage(Collections.singletonList("PLAIN")), ex.getMessage());
  }
}
