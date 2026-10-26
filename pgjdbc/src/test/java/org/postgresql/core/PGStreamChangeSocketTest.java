/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

/**
 * A socket that replaces the socket of a stream, such as the SSL socket layered over it, keeps the
 * {@code TCP_NODELAY} setting of the socket it replaces, and the first socket of a stream gets
 * {@code TCP_NODELAY} on. The SSL upgrade used to turn {@code TCP_NODELAY} on, which undid
 * {@code tcpNoDelay=false}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PGStreamChangeSocketTest {

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void anSslSocketKeepsTheTcpNoDelayOfThePlainSocket(boolean tcpNoDelay) throws IOException {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      PGStream stream = connect(server);
      try (Socket accepted = server.accept()) {
        Socket plain = stream.getSocket();
        plain.setTcpNoDelay(tcpNoDelay);
        // The TLS handshake starts on the first read or write, and the test does neither.
        Socket ssl = ((SSLSocketFactory) SSLSocketFactory.getDefault())
            .createSocket(plain, "127.0.0.1", server.getLocalPort(), true);

        stream.changeSocket(ssl);

        assertEquals(tcpNoDelay, stream.getSocket().getTcpNoDelay(),
            "TCP_NODELAY after changeSocket");
      } finally {
        stream.close();
      }
    }
  }

  @Test
  void theFirstSocketOfAStreamHasTcpNoDelayOn() throws IOException {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      PGStream stream = connect(server);
      try (Socket accepted = server.accept()) {
        assertTrue(stream.getSocket().getTcpNoDelay(), "TCP_NODELAY of a new stream");
      } finally {
        stream.close();
      }
    }
  }

  private static PGStream connect(ServerSocket server) throws IOException {
    return new PGStream(SocketFactory.getDefault(),
        new HostSpec("127.0.0.1", server.getLocalPort()), 0, 8192);
  }
}
