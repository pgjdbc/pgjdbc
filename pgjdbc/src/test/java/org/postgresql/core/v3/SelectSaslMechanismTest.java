/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.postgresql.PGProperty;
import org.postgresql.core.AuthMethod;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.TestLogHandler;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * {@link ConnectionFactoryImpl#selectSaslMechanism} decides from the offered mechanisms, the OAuth
 * configuration, {@code requireAuth} and {@code channelBinding} alone, and logs why, so each row
 * of the table below is one case.
 */
class SelectSaslMechanismTest {

  private static final String OAUTHBEARER = OauthAuthenticator.SASL_MECHANISM;

  private static final List<String> SCRAM_ONLY =
      Arrays.asList("SCRAM-SHA-256", "SCRAM-SHA-256-PLUS");
  private static final List<String> SCRAM_WITHOUT_PLUS =
      Collections.singletonList("SCRAM-SHA-256");
  private static final List<String> OAUTH_AND_SCRAM_WITHOUT_PLUS =
      Arrays.asList(OAUTHBEARER, "SCRAM-SHA-256");
  private static final List<String> SCRAM_PLUS_ONLY =
      Collections.singletonList("SCRAM-SHA-256-PLUS");
  private static final List<String> OAUTH_ONLY = Collections.singletonList(OAUTHBEARER);
  private static final List<String> OAUTH_FIRST =
      Arrays.asList(OAUTHBEARER, "SCRAM-SHA-256", "SCRAM-SHA-256-PLUS");
  private static final List<String> SCRAM_FIRST =
      Arrays.asList("SCRAM-SHA-256", "SCRAM-SHA-256-PLUS", OAUTHBEARER);
  private static final List<String> PLAIN_ONLY = Collections.singletonList("PLAIN");
  private static final List<String> PLAIN_AND_OAUTH = Arrays.asList("PLAIN", OAUTHBEARER);

  private static final String REQUIRE_AUTH_MESSAGE =
      GT.tr("Authentication method is not allowed by requireAuth");

  private static final String OAUTH_CHANNEL_BINDING_MESSAGE =
      GT.tr("Channel binding is not supported for OAuth authentication.");

  private static final String NO_PLUS_MECHANISM_MESSAGE =
      GT.tr("Channel Binding is required, but server did not offer an "
          + "authentication method that supports channel binding");

  private static final String OAUTH_NOT_CONFIGURED_MESSAGE =
      GT.tr("The server requested OAuth authentication, but neither {0} nor {1} is configured.",
          PGProperty.OAUTH_TOKEN.getName(),
          PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.getName());

  private static final String CONFIGURED = "oauthToken or oauthTokenProviderClassName is set";
  private static final String NOT_OFFERED = "the server did not offer OAUTHBEARER";
  private static final String NOT_ALLOWED = "requireAuth does not allow oauth";
  private static final String NOT_CONFIGURED =
      "neither oauthToken nor oauthTokenProviderClassName is set";
  private static final String CHANNEL_BINDING =
      "channelBinding=require, which OAUTHBEARER cannot provide";

  /** How the connection properties configure OAuth. */
  private enum OauthConfig {
    TOKEN(PGProperty.OAUTH_TOKEN, "token"),
    PROVIDER(PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME, "com.example.Provider"),
    EMPTY_TOKEN(PGProperty.OAUTH_TOKEN, ""),
    EMPTY_PROVIDER(PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME, ""),
    NONE(null, null);

    private final @Nullable PGProperty property;
    private final @Nullable String value;

    OauthConfig(@Nullable PGProperty property, @Nullable String value) {
      this.property = property;
      this.value = value;
    }

    Properties toProperties() {
      Properties info = new Properties();
      if (property != null) {
        property.set(info, value);
      }
      return info;
    }
  }

  /** The mechanism chosen and the reason logged for it, or the message of the refusal. */
  private static final class Outcome {
    private final @Nullable AuthMethod method;
    private final @Nullable String logged;
    private final @Nullable String refusal;

    private Outcome(@Nullable AuthMethod method, @Nullable String logged,
        @Nullable String refusal) {
      this.method = method;
      this.logged = logged;
      this.refusal = refusal;
    }

    static Outcome chooses(List<String> offered, AuthMethod method, String reason) {
      String chosen = method == AuthMethod.OAUTH ? OAUTHBEARER : "SCRAM";
      return new Outcome(method, MessageFormat.format(
          "The server offered SASL mechanisms {0}, using {1} because {2}",
          offered, chosen, reason), null);
    }

    static Outcome refuses(String message) {
      return new Outcome(null, null, message);
    }

    @Override
    public String toString() {
      return refusal != null ? "refuses: " + refusal : "chooses " + method;
    }
  }

  private static Arguments row(String name, List<String> offered, OauthConfig config,
      @Nullable String requireAuth, ChannelBinding channelBinding, Outcome outcome) {
    return argumentSet(name, offered, config, requireAuth, channelBinding, outcome);
  }

  private static Outcome oauth(List<String> offered) {
    return Outcome.chooses(offered, AuthMethod.OAUTH, CONFIGURED);
  }

  private static Outcome scram(List<String> offered, String reason) {
    return Outcome.chooses(offered, AuthMethod.SCRAM_SHA_256, reason);
  }

  private static Outcome refuses(String message) {
    return Outcome.refuses(message);
  }

  static Stream<Arguments> decisions() {
    ChannelBinding prefer = ChannelBinding.PREFER;
    ChannelBinding require = ChannelBinding.REQUIRE;
    return Stream.of(
        // OAUTHBEARER is used when it is offered, allowed and configured, unless
        // channelBinding=require.
        row("OAuth only, token", OAUTH_ONLY, OauthConfig.TOKEN, null, prefer,
            oauth(OAUTH_ONLY)),
        row("OAuth first, token", OAUTH_FIRST, OauthConfig.TOKEN, null, prefer,
            oauth(OAUTH_FIRST)),
        row("SCRAM first, token", SCRAM_FIRST, OauthConfig.TOKEN, null, prefer,
            oauth(SCRAM_FIRST)),
        row("OAuth first, provider", OAUTH_FIRST, OauthConfig.PROVIDER, null, prefer,
            oauth(OAUTH_FIRST)),
        row("PLAIN and OAuth, token", PLAIN_AND_OAUTH, OauthConfig.TOKEN, null, prefer,
            oauth(PLAIN_AND_OAUTH)),
        row("OAuth first, token, requireAuth=oauth", OAUTH_FIRST, OauthConfig.TOKEN, "oauth",
            prefer, oauth(OAUTH_FIRST)),
        row("OAuth first, token, requireAuth=!scram-sha-256", OAUTH_FIRST, OauthConfig.TOKEN,
            "!scram-sha-256", prefer, oauth(OAUTH_FIRST)),
        row("OAuth first, token, channelBinding=disable", OAUTH_FIRST, OauthConfig.TOKEN, null,
            ChannelBinding.DISABLE, oauth(OAUTH_FIRST)),

        // Otherwise SCRAM, when it is offered and allowed.
        row("SCRAM only, token", SCRAM_ONLY, OauthConfig.TOKEN, null, prefer,
            scram(SCRAM_ONLY, NOT_OFFERED)),
        row("SCRAM-SHA-256-PLUS only, token", SCRAM_PLUS_ONLY, OauthConfig.TOKEN, null, prefer,
            scram(SCRAM_PLUS_ONLY, NOT_OFFERED)),
        row("SCRAM only, no OAuth configuration", SCRAM_ONLY, OauthConfig.NONE, null, prefer,
            scram(SCRAM_ONLY, NOT_CONFIGURED)),
        row("OAuth first, no OAuth configuration", OAUTH_FIRST, OauthConfig.NONE, null, prefer,
            scram(OAUTH_FIRST, NOT_CONFIGURED)),
        row("OAuth first, empty oauthToken", OAUTH_FIRST, OauthConfig.EMPTY_TOKEN, null, prefer,
            scram(OAUTH_FIRST, NOT_CONFIGURED)),
        row("OAuth first, empty oauthTokenProviderClassName", OAUTH_FIRST,
            OauthConfig.EMPTY_PROVIDER, null, prefer, scram(OAUTH_FIRST, NOT_CONFIGURED)),
        row("OAuth first, token, requireAuth=!oauth", OAUTH_FIRST, OauthConfig.TOKEN, "!oauth",
            prefer, scram(OAUTH_FIRST, NOT_ALLOWED)),
        row("OAuth first, token, requireAuth=scram-sha-256", OAUTH_FIRST, OauthConfig.TOKEN,
            "scram-sha-256", prefer, scram(OAUTH_FIRST, NOT_ALLOWED)),
        row("OAuth first, token, channelBinding=require", OAUTH_FIRST, OauthConfig.TOKEN, null,
            require, scram(OAUTH_FIRST, CHANNEL_BINDING)),
        row("OAuth first, token, channelBinding=require, requireAuth=!md5", OAUTH_FIRST,
            OauthConfig.TOKEN, "!md5", require, scram(OAUTH_FIRST, CHANNEL_BINDING)),

        // Refusals.
        row("OAuth only, token, requireAuth=!oauth", OAUTH_ONLY, OauthConfig.TOKEN, "!oauth",
            prefer, refuses(REQUIRE_AUTH_MESSAGE)),
        row("SCRAM only, token, requireAuth=oauth", SCRAM_ONLY, OauthConfig.TOKEN, "oauth",
            prefer, refuses(REQUIRE_AUTH_MESSAGE)),
        row("OAuth first, token, requireAuth=md5", OAUTH_FIRST, OauthConfig.TOKEN, "md5",
            prefer, refuses(REQUIRE_AUTH_MESSAGE)),
        row("OAuth only, no OAuth configuration", OAUTH_ONLY, OauthConfig.NONE, null, prefer,
            refuses(OAUTH_NOT_CONFIGURED_MESSAGE)),
        row("OAuth only, empty oauthToken", OAUTH_ONLY, OauthConfig.EMPTY_TOKEN, null, prefer,
            refuses(OAUTH_NOT_CONFIGURED_MESSAGE)),
        row("OAuth only, empty oauthTokenProviderClassName", OAUTH_ONLY,
            OauthConfig.EMPTY_PROVIDER, null, prefer, refuses(OAUTH_NOT_CONFIGURED_MESSAGE)),
        row("OAuth first, no OAuth configuration, requireAuth=oauth", OAUTH_FIRST,
            OauthConfig.NONE, "oauth", prefer, refuses(OAUTH_NOT_CONFIGURED_MESSAGE)),
        row("OAuth only, no OAuth configuration, channelBinding=require", OAUTH_ONLY,
            OauthConfig.NONE, null, require, refuses(OAUTH_NOT_CONFIGURED_MESSAGE)),
        row("OAuth only, token, channelBinding=require", OAUTH_ONLY, OauthConfig.TOKEN, null,
            require, refuses(OAUTH_CHANNEL_BINDING_MESSAGE)),
        row("OAuth first, token, channelBinding=require, requireAuth=oauth", OAUTH_FIRST,
            OauthConfig.TOKEN, "oauth", require, refuses(OAUTH_CHANNEL_BINDING_MESSAGE)),
        row("SCRAM without PLUS, channelBinding=require", SCRAM_WITHOUT_PLUS, OauthConfig.NONE,
            null, require, refuses(NO_PLUS_MECHANISM_MESSAGE)),
        row("OAuth and SCRAM without PLUS, token, channelBinding=require",
            OAUTH_AND_SCRAM_WITHOUT_PLUS, OauthConfig.TOKEN, null, require,
            refuses(NO_PLUS_MECHANISM_MESSAGE)),
        row("PLAIN only, token", PLAIN_ONLY, OauthConfig.TOKEN, null, prefer,
            refuses(GT.tr("The server requested SASL authentication with mechanisms {0}, "
                + "but the driver supports none of them.", PLAIN_ONLY))));
  }

  private TestLogHandler logHandler;
  private Logger logger;
  private Level previousLevel;
  private boolean previousUseParentHandlers;

  @BeforeEach
  void setUp() {
    Thread testThread = Thread.currentThread();
    logHandler = new TestLogHandler() {
      @Override
      public void publish(LogRecord record) {
        // Tests running in parallel open connections and log through the same logger.
        if (Thread.currentThread() == testThread) {
          super.publish(record);
        }
      }
    };
    logger = Logger.getLogger(ConnectionFactoryImpl.class.getName());
    previousLevel = logger.getLevel();
    previousUseParentHandlers = logger.getUseParentHandlers();
    logger.addHandler(logHandler);
    logger.setLevel(Level.FINE);
    // Record the messages here without also emitting them to the console.
    logger.setUseParentHandlers(false);
  }

  @AfterEach
  void tearDown() {
    logger.removeHandler(logHandler);
    logger.setLevel(previousLevel);
    logger.setUseParentHandlers(previousUseParentHandlers);
  }

  @ParameterizedTest
  @MethodSource
  void decisions(List<String> offered, OauthConfig config, @Nullable String requireAuth,
      ChannelBinding channelBinding, Outcome expected) throws PSQLException {
    Properties info = config.toProperties();

    if (expected.refusal != null) {
      PSQLException ex = assertThrows(PSQLException.class,
          () -> ConnectionFactoryImpl.selectSaslMechanism(offered, info,
              AuthMethod.parseRequireAuth(requireAuth), channelBinding));
      assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
      assertEquals(expected.refusal, ex.getMessage());
      assertEquals(Collections.emptyList(), loggedMessages());
    } else {
      assertEquals(expected.method, ConnectionFactoryImpl.selectSaslMechanism(offered, info,
          AuthMethod.parseRequireAuth(requireAuth), channelBinding));
      assertEquals(Collections.singletonList(expected.logged), loggedMessages());
    }
  }

  private List<String> loggedMessages() {
    List<String> messages = new ArrayList<>();
    for (LogRecord record : logHandler.records) {
      Object[] parameters = record.getParameters();
      messages.add(parameters == null
          ? record.getMessage()
          : MessageFormat.format(record.getMessage(), parameters));
    }
    return messages;
  }
}
