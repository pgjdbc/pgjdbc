/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.Mockito.when;

import org.postgresql.core.PGStream;
import org.postgresql.jdbc.SslMode;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.TestLogHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.Socket;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import javax.net.ssl.SSLSocket;

@ExtendWith(MockitoExtension.class)
class OAuthAuthenticatorTest {

  /** How the connection under test is encrypted, if at all. */
  private enum Transport {
    TLS,
    GSS,
    PLAINTEXT
  }

  private static final String UNENCRYPTED_WARNING =
      "WARNING: oauthAllowInsecureConnection=true: OAUTHBEARER is being used over an unencrypted "
          + "connection, so the bearer token is sent in the clear. RFC 7628 §3 requires a "
          + "secure channel. Do not use this configuration in production.";

  private static String unverifiedTlsWarning(SslMode sslMode) {
    return "WARNING: oauthAllowInsecureConnection=true: OAUTHBEARER is being used over a TLS "
        + "connection whose server certificate is not verified (sslmode=" + sslMode.value
        + "), so the bearer token may be disclosed to an impostor. RFC 7628 §3 requires a "
        + "secure channel. Do not use this configuration in production.";
  }

  private static String unverifiedTlsRefusal(SslMode sslMode) {
    return GT.tr("OAUTHBEARER authentication requires the server certificate to be verified "
        + "(RFC 7628 §3), and sslmode={0} does not verify it. Use sslmode=verify-ca or "
        + "sslmode=verify-full, or set oauthAllowInsecureConnection=true to override "
        + "(testing only).", sslMode.value);
  }

  @Mock
  private SSLSocket sslSocket;

  @Mock
  private Socket socket;

  @Mock
  private PGStream pgStream;

  private TestLogHandler logHandler;
  private Logger oauthLogger;
  private Level previousLevel;
  private boolean previousUseParentHandlers;

  @BeforeEach
  void setUp() {
    logHandler = new TestLogHandler();
    oauthLogger = Logger.getLogger(OAuthAuthenticator.class.getName());
    previousLevel = oauthLogger.getLevel();
    previousUseParentHandlers = oauthLogger.getUseParentHandlers();
    oauthLogger.addHandler(logHandler);
    oauthLogger.setLevel(Level.ALL);
    // Record the messages here without also emitting them to the console.
    oauthLogger.setUseParentHandlers(false);
  }

  @AfterEach
  void tearDown() {
    oauthLogger.removeHandler(logHandler);
    oauthLogger.setLevel(previousLevel);
    oauthLogger.setUseParentHandlers(previousUseParentHandlers);
  }

  static Stream<Arguments> accepts() {
    return Stream.of(
        argumentSet("TLS, sslmode=verify-ca, secure connection required",
            Transport.TLS, SslMode.VERIFY_CA, false, Collections.emptyList()),
        argumentSet("TLS, sslmode=verify-full, secure connection required",
            Transport.TLS, SslMode.VERIFY_FULL, false, Collections.emptyList()),
        argumentSet("TLS, sslmode=verify-ca, insecure connection allowed",
            Transport.TLS, SslMode.VERIFY_CA, true, Collections.emptyList()),
        argumentSet("TLS, sslmode=verify-full, insecure connection allowed",
            Transport.TLS, SslMode.VERIFY_FULL, true, Collections.emptyList()),
        argumentSet("GSS encryption, secure connection required",
            Transport.GSS, SslMode.DISABLE, false, Collections.emptyList()),
        argumentSet("GSS encryption, insecure connection allowed",
            Transport.GSS, SslMode.DISABLE, true, Collections.emptyList()),
        argumentSet("TLS, sslmode=prefer, insecure connection allowed",
            Transport.TLS, SslMode.PREFER, true,
            Collections.singletonList(unverifiedTlsWarning(SslMode.PREFER))),
        argumentSet("TLS, sslmode=require, insecure connection allowed",
            Transport.TLS, SslMode.REQUIRE, true,
            Collections.singletonList(unverifiedTlsWarning(SslMode.REQUIRE))),
        argumentSet("no encryption, insecure connection allowed",
            Transport.PLAINTEXT, SslMode.DISABLE, true,
            Collections.singletonList(UNENCRYPTED_WARNING)));
  }

  @ParameterizedTest
  @MethodSource
  void accepts(Transport transport, SslMode sslMode, boolean allowInsecure,
      List<String> expectedLog) {
    stubTransport(transport);

    assertDoesNotThrow(() -> new OAuthAuthenticator(pgStream, sslMode, allowInsecure));
    assertEquals(expectedLog, loggedMessages());
  }

  static Stream<Arguments> refuses() {
    return Stream.of(
        argumentSet("TLS, sslmode=prefer", Transport.TLS, SslMode.PREFER,
            unverifiedTlsRefusal(SslMode.PREFER)),
        argumentSet("TLS, sslmode=require", Transport.TLS, SslMode.REQUIRE,
            unverifiedTlsRefusal(SslMode.REQUIRE)),
        argumentSet("no encryption", Transport.PLAINTEXT, SslMode.DISABLE,
            GT.tr("OAUTHBEARER authentication requires a connection to a verified server "
                + "(TLS with sslmode=verify-ca or higher, or GSS encryption) (RFC 7628 §3). "
                + "Set oauthAllowInsecureConnection=true to override (testing only).")));
  }

  @ParameterizedTest
  @MethodSource
  void refuses(Transport transport, SslMode sslMode, String expectedMessage) {
    stubTransport(transport);

    PSQLException ex = assertThrows(PSQLException.class,
        () -> new OAuthAuthenticator(pgStream, sslMode, false));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(expectedMessage, ex.getMessage());
    assertEquals(Collections.emptyList(), loggedMessages());
  }

  private void stubTransport(Transport transport) {
    switch (transport) {
      case TLS:
        when(pgStream.getSocket()).thenReturn(sslSocket);
        break;
      case GSS:
        when(pgStream.isGssEncrypted()).thenReturn(true);
        break;
      default:
        when(pgStream.getSocket()).thenReturn(socket);
        break;
    }
  }

  private List<String> loggedMessages() {
    List<String> messages = new ArrayList<>();
    for (LogRecord record : logHandler.records) {
      String message = record.getMessage();
      Object[] parameters = record.getParameters();
      if (message != null && parameters != null && parameters.length > 0) {
        message = MessageFormat.format(message, parameters);
      }
      messages.add(record.getLevel() + ": " + message);
    }
    return messages;
  }
}
