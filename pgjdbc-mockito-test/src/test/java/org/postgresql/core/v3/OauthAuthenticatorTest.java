/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.postgresql.core.PGStream;
import org.postgresql.jdbc.SslMode;
import org.postgresql.ssl.LibPQFactory;
import org.postgresql.ssl.NonValidatingFactory;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.TestLogHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.Socket;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import javax.net.ssl.SSLSocket;

/**
 * OAUTHBEARER goes ahead without a log record over GSS encryption, or over TLS with
 * {@code sslmode=verify-ca} or {@code verify-full}. Any other connection is refused, and
 * {@code oauthAllowInsecureConnection} turns that refusal into a WARNING the first time and FINE
 * afterwards. {@code sslfactory=org.postgresql.ssl.NonValidatingFactory} counts as unverified
 * whatever {@code sslmode} says. The client message that carries the token is zeroed once it is
 * sent.
 */
@ExtendWith(MockitoExtension.class)
class OauthAuthenticatorTest {

  /** How the connection under test is encrypted, if at all. */
  private enum Transport {
    TLS,
    GSS,
    PLAINTEXT
  }

  private static final HostSpec HOST = new HostSpec("db.example.com", 5432);

  private static final String LIBPQ_FACTORY = LibPQFactory.class.getName();

  private static final String NON_VALIDATING_FACTORY = NonValidatingFactory.class.getName();

  private static String unencryptedWarning(SslMode sslMode) {
    return "Sending the OAuth bearer token to db.example.com:5432 over an unencrypted connection "
        + "(sslmode=" + sslMode.value + "), because oauthAllowInsecureConnection=true";
  }

  private static String unverifiedTlsWarning(SslMode sslMode) {
    return unverifiedTlsWarning("sslmode=" + sslMode.value);
  }

  private static String unverifiedTlsWarning(String unverifiedBy) {
    return "Sending the OAuth bearer token to db.example.com:5432 over TLS without verifying the "
        + "server certificate (" + unverifiedBy + "), because oauthAllowInsecureConnection=true";
  }

  private static String nonValidatingFactoryRefusal() {
    return GT.tr("OAUTHBEARER sends the bearer token only to a verified server, and "
        + "sslfactory={0} does not verify the server certificate. Remove sslfactory, or set "
        + "it to a factory that verifies the certificate (RFC 6750 section 5.3).",
        NON_VALIDATING_FACTORY);
  }

  private static String unverifiedTlsRefusal(SslMode sslMode) {
    return GT.tr("OAUTHBEARER sends the bearer token only to a verified server, and "
        + "sslmode={0} does not verify the server certificate. Set sslmode=verify-full "
        + "or verify-ca (RFC 6750 section 5.3).", sslMode.value);
  }

  private static String unencryptedRefusal(SslMode sslMode) {
    return GT.tr("OAUTHBEARER sends the bearer token only over an encrypted connection to a "
        + "verified server, and this connection is not encrypted (sslmode={0}). Set "
        + "sslmode=verify-full or verify-ca, or use GSS encryption (RFC 7628 section 3, "
        + "RFC 6750 section 5.3).",
        sslMode.value);
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
    oauthLogger = Logger.getLogger(OauthAuthenticator.class.getName());
    previousLevel = oauthLogger.getLevel();
    previousUseParentHandlers = oauthLogger.getUseParentHandlers();
    oauthLogger.addHandler(logHandler);
    oauthLogger.setLevel(Level.ALL);
    // Record the messages here without also emitting them to the console.
    oauthLogger.setUseParentHandlers(false);
    OauthAuthenticator.resetInsecureConnectionWarnings();
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
            Transport.TLS, SslMode.VERIFY_CA, LIBPQ_FACTORY, false, Collections.emptyList()),
        argumentSet("TLS, sslmode=verify-full, secure connection required",
            Transport.TLS, SslMode.VERIFY_FULL, LIBPQ_FACTORY, false, Collections.emptyList()),
        argumentSet("TLS, sslmode=verify-ca, insecure connection allowed",
            Transport.TLS, SslMode.VERIFY_CA, LIBPQ_FACTORY, true, Collections.emptyList()),
        argumentSet("TLS, sslmode=verify-full, insecure connection allowed",
            Transport.TLS, SslMode.VERIFY_FULL, LIBPQ_FACTORY, true, Collections.emptyList()),
        argumentSet("GSS encryption, secure connection required",
            Transport.GSS, SslMode.DISABLE, LIBPQ_FACTORY, false, Collections.emptyList()),
        argumentSet("GSS encryption, insecure connection allowed",
            Transport.GSS, SslMode.DISABLE, LIBPQ_FACTORY, true, Collections.emptyList()),
        argumentSet("TLS, sslmode=prefer, insecure connection allowed",
            Transport.TLS, SslMode.PREFER, LIBPQ_FACTORY, true,
            Collections.singletonList("WARNING: " + unverifiedTlsWarning(SslMode.PREFER))),
        argumentSet("TLS, sslmode=require, insecure connection allowed",
            Transport.TLS, SslMode.REQUIRE, LIBPQ_FACTORY, true,
            Collections.singletonList("WARNING: " + unverifiedTlsWarning(SslMode.REQUIRE))),
        argumentSet("TLS, sslmode=verify-full, NonValidatingFactory, insecure connection allowed",
            Transport.TLS, SslMode.VERIFY_FULL, NON_VALIDATING_FACTORY, true,
            Collections.singletonList(
                "WARNING: " + unverifiedTlsWarning("sslfactory=" + NON_VALIDATING_FACTORY))),
        argumentSet("no encryption, insecure connection allowed",
            Transport.PLAINTEXT, SslMode.DISABLE, LIBPQ_FACTORY, true,
            Collections.singletonList("WARNING: " + unencryptedWarning(SslMode.DISABLE))));
  }

  @ParameterizedTest
  @MethodSource
  void accepts(Transport transport, SslMode sslMode, String sslFactory, boolean allowInsecure,
      List<String> expectedLog) {
    stubTransport(transport);
    if (!expectedLog.isEmpty()) {
      when(pgStream.getHostSpec()).thenReturn(HOST);
    }

    assertDoesNotThrow(
        () -> new OauthAuthenticator(pgStream, sslMode, sslFactory, allowInsecure));
    assertEquals(expectedLog, loggedMessages());
  }

  static Stream<Arguments> refuses() {
    return Stream.of(
        argumentSet("TLS, sslmode=prefer", Transport.TLS, SslMode.PREFER, LIBPQ_FACTORY,
            unverifiedTlsRefusal(SslMode.PREFER)),
        argumentSet("TLS, sslmode=require", Transport.TLS, SslMode.REQUIRE, LIBPQ_FACTORY,
            unverifiedTlsRefusal(SslMode.REQUIRE)),
        argumentSet("TLS, sslmode=verify-ca, NonValidatingFactory", Transport.TLS,
            SslMode.VERIFY_CA, NON_VALIDATING_FACTORY, nonValidatingFactoryRefusal()),
        argumentSet("TLS, sslmode=verify-full, NonValidatingFactory", Transport.TLS,
            SslMode.VERIFY_FULL, NON_VALIDATING_FACTORY, nonValidatingFactoryRefusal()),
        argumentSet("TLS, sslmode=require, NonValidatingFactory names the sslmode", Transport.TLS,
            SslMode.REQUIRE, NON_VALIDATING_FACTORY, unverifiedTlsRefusal(SslMode.REQUIRE)),
        argumentSet("no encryption, sslmode=disable", Transport.PLAINTEXT, SslMode.DISABLE,
            LIBPQ_FACTORY, unencryptedRefusal(SslMode.DISABLE)),
        argumentSet("no encryption, sslmode=prefer fell back to plaintext", Transport.PLAINTEXT,
            SslMode.PREFER, LIBPQ_FACTORY, unencryptedRefusal(SslMode.PREFER)));
  }

  @ParameterizedTest
  @MethodSource
  void refuses(Transport transport, SslMode sslMode, String sslFactory, String expectedMessage) {
    stubTransport(transport);

    PSQLException ex = assertThrows(PSQLException.class,
        () -> new OauthAuthenticator(pgStream, sslMode, sslFactory, false));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(expectedMessage, ex.getMessage());
    assertEquals(Collections.emptyList(), loggedMessages());
  }

  static Stream<Arguments> logsTheInsecureConnectionAtWarningOnlyOnce() {
    return Stream.of(
        argumentSet("TLS, sslmode=require", Transport.TLS, SslMode.REQUIRE,
            unverifiedTlsWarning(SslMode.REQUIRE)),
        argumentSet("no encryption", Transport.PLAINTEXT, SslMode.DISABLE,
            unencryptedWarning(SslMode.DISABLE)));
  }

  @ParameterizedTest
  @MethodSource
  void logsTheInsecureConnectionAtWarningOnlyOnce(Transport transport, SslMode sslMode,
      String message) throws PSQLException {
    stubTransport(transport);
    when(pgStream.getHostSpec()).thenReturn(HOST);

    new OauthAuthenticator(pgStream, sslMode, LIBPQ_FACTORY, true);
    new OauthAuthenticator(pgStream, sslMode, LIBPQ_FACTORY, true);

    assertEquals(Arrays.asList("WARNING: " + message, "FINE: " + message), loggedMessages());
  }

  /**
   * The client message holds the token, so the driver zeroes it once it is written, even when
   * the write fails.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wipesTheClientMessage(boolean sendFails) throws Exception {
    stubTransport(Transport.GSS);
    if (sendFails) {
      // The mechanism name is written first, then the client message.
      doNothing().doThrow(new IOException("connection reset"))
          .when(pgStream).send(any(byte[].class));
    }
    OauthAuthenticator authenticator =
        new OauthAuthenticator(pgStream, SslMode.DISABLE, LIBPQ_FACTORY, false);

    if (sendFails) {
      assertThrows(IOException.class,
          () -> authenticator.handleAuthenticationSASL("secret-token".toCharArray()));
    } else {
      authenticator.handleAuthenticationSASL("secret-token".toCharArray());
    }

    ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
    verify(pgStream, times(2)).send(sent.capture());
    byte[] clientMessage = sent.getAllValues().get(1);
    assertArrayEquals(new byte[clientMessage.length], clientMessage,
        "the client message should be zeroed after it was written");
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
