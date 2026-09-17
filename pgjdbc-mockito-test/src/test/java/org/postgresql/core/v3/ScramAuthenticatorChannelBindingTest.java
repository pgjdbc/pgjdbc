/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.postgresql.core.PGStream;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * Regression test for GHSA-j92g-9f8w-j867. Under {@code channelBinding=require}, the driver must
 * fail the connection rather than fall back to a non-PLUS mechanism when it cannot extract channel
 * binding data from the peer certificate. The test drives {@link ScramAuthenticator} over a mocked
 * TLS socket, so it needs no live server.
 */
class ScramAuthenticatorChannelBindingTest {

  private static final List<String> MECHANISMS =
      Arrays.asList("SCRAM-SHA-256", "SCRAM-SHA-256-PLUS");

  @Test
  void requireRejectsCertificateWithoutChannelBindingHash() throws Exception {
    // A certificate whose signature algorithm yields no channel binding hash, such as Ed25519.
    // getSigAlgName is stubbed rather than parsing a real Ed25519 certificate: the JDK reports the
    // algorithm as the OID "1.3.101.112" on older JDK 11 builds and as "Ed25519" on newer ones, so
    // a parsed certificate would make the test JDK-version-dependent.
    X509Certificate cert = mock(X509Certificate.class);
    when(cert.getSigAlgName()).thenReturn("Ed25519");
    SSLSession session = mock(SSLSession.class);
    when(session.getPeerCertificates()).thenReturn(new Certificate[]{cert});
    SSLSocket socket = mock(SSLSocket.class);
    when(socket.getSession()).thenReturn(session);
    PGStream stream = mock(PGStream.class);
    when(stream.getSocket()).thenReturn(socket);

    PSQLException ex = assertThrows(PSQLException.class,
        () -> new ScramAuthenticator("secret".toCharArray(), stream, ChannelBinding.REQUIRE, 0,
            MECHANISMS));

    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    // The message names the offending signature algorithm so an operator can act.
    assertTrue(ex.getMessage().contains("Ed25519"), ex.getMessage());
  }

  @Test
  void requireRejectsConnectionWithoutSsl() throws Exception {
    PGStream stream = mock(PGStream.class);
    when(stream.getSocket()).thenReturn(mock(Socket.class));

    PSQLException ex = assertThrows(PSQLException.class,
        () -> new ScramAuthenticator("secret".toCharArray(), stream, ChannelBinding.REQUIRE, 0,
            MECHANISMS));

    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
  }
}
