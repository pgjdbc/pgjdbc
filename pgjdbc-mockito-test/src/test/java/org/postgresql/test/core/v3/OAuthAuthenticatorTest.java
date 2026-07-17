/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core.v3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import org.postgresql.core.PGStream;
import org.postgresql.core.v3.OAuthAuthenticator;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.TestLogHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.Socket;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocket;

@ExtendWith(MockitoExtension.class)
class OAuthAuthenticatorTest {

  private static final Pattern UNENCRYPTED_WARNING_PATTERN =
      Pattern.compile("OAUTHBEARER is being used over an unencrypted connection. This violates RFC 7628 §4. "
                + "DO NOT USE THIS CONFIGURATION IN PRODUCTION!");

  @Mock
  private SSLSocket sslSocket;

  @Mock
  private Socket socket;

  @Mock
  private PGStream pgStream;

  private TestLogHandler logHandler;
  private Logger oauthLogger;
  private Level previousLevel;

  @BeforeEach
  void setUp() {
    logHandler = new TestLogHandler();
    oauthLogger = Logger.getLogger(OAuthAuthenticator.class.getName());
    previousLevel = oauthLogger.getLevel();
    oauthLogger.addHandler(logHandler);
    oauthLogger.setLevel(Level.ALL);
  }

  @AfterEach
  void tearDown() {
    oauthLogger.removeHandler(logHandler);
    oauthLogger.setLevel(previousLevel);
  }

  @Test
  void tlsWithEncryptionRequirement() {
    when(pgStream.getSocket()).thenReturn(sslSocket);
    assertDoesNotThrow(() -> new OAuthAuthenticator(pgStream, false));
    assertEquals(0, logHandler.records.size(), "no log records expected over TLS");
  }

  @Test
  void tlsWithoutEncryptionRequirement() {
    when(pgStream.getSocket()).thenReturn(sslSocket);

    assertDoesNotThrow(() -> new OAuthAuthenticator(pgStream, true));
    assertEquals(0, logHandler.records.size(), "no log records expected over TLS");
  }

  @Test
  void gssWithEncryptionRequirement() {
    when(pgStream.getSocket()).thenReturn(socket);
    when(pgStream.isGssEncrypted()).thenReturn(true);

    assertDoesNotThrow(() -> new OAuthAuthenticator(pgStream, false));
    assertEquals(0, logHandler.records.size(), "no log records expected over a GSS");
  }

  @Test
  void gssWithoutEncryptionRequirement() {
    when(pgStream.getSocket()).thenReturn(socket);
    when(pgStream.isGssEncrypted()).thenReturn(true);

    assertDoesNotThrow(() -> new OAuthAuthenticator(pgStream, true));
    assertEquals(0, logHandler.records.size(), "no log records expected over a GSS");
  }

  @Test
  void unencryptedWithEncryptionRequirement() {
    when(pgStream.getSocket()).thenReturn(socket);

    PSQLException ex = assertThrows(PSQLException.class,
        () -> new OAuthAuthenticator(pgStream, false));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals("OAUTHBEARER authentication requires an encrypted connection (TLS or GSS) "
              + "(RFC 7628 §4). Set oauthAllowUnencryptedConnection=true to override (testing only).", ex.getMessage());
    assertEquals(0, logHandler.records.size(),"no log records expected when throwing");
  }

  @Test
  void unencryptedWithoutEncryptionRequirement() {
    when(pgStream.getSocket()).thenReturn(socket);

    assertDoesNotThrow(() -> new OAuthAuthenticator(pgStream, true));

    List<LogRecord> warnings = logHandler.getRecordsMatching(UNENCRYPTED_WARNING_PATTERN);
    assertEquals(1, warnings.size());
    assertEquals(Level.WARNING, warnings.get(0).getLevel());
  }
}
