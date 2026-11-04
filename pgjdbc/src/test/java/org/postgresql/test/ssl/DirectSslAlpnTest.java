/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;
import org.postgresql.util.internal.FileUtils;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * The tests connect to a local TLS server that is not PostgreSQL. It records the length of the
 * first message the driver sends after the handshake, or -1 when the driver sends nothing.
 */
class DirectSslAlpnTest {
  private static SSLServerSocket serverSocket(boolean selectPostgresql) throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (InputStream in = FileUtils.newBufferedInputStream(TestUtil.getSslTestCertPath("goodclient.p12"))) {
      keyStore.load(in, "sslpwd".toCharArray());
    }
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, "sslpwd".toCharArray());
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(kmf.getKeyManagers(), null, null);
    SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0);
    if (selectPostgresql) {
      SSLParameters parameters = server.getSSLParameters();
      parameters.setApplicationProtocols(new String[]{"postgresql"});
      server.setSSLParameters(parameters);
    }
    return server;
  }

  private static CompletableFuture<Integer> firstMessageLength(SSLServerSocket server) {
    return CompletableFuture.supplyAsync(() -> {
      try (SSLSocket socket = (SSLSocket) server.accept()) {
        socket.setSoTimeout(10000);
        socket.startHandshake();
        return new DataInputStream(socket.getInputStream()).readInt();
      } catch (Exception e) {
        return -1;
      }
    });
  }

  private static void connect(int port) throws SQLException {
    Properties props = new Properties();
    PGProperty.USER.set(props, "test");
    PGProperty.PASSWORD.set(props, "test");
    PGProperty.SSL_MODE.set(props, "require");
    PGProperty.SSL_NEGOTIATION.set(props, "direct");
    PGProperty.CONNECT_TIMEOUT.set(props, 10);
    PGProperty.SOCKET_TIMEOUT.set(props, 10);
    DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/test", props).close();
  }

  @Test
  void peerWithoutAlpnIsRefused() throws Exception {
    try (SSLServerSocket server = serverSocket(false)) {
      CompletableFuture<Integer> received = firstMessageLength(server);
      SQLException e = assertThrows(SQLException.class, () -> connect(server.getLocalPort()));
      assertEquals(PSQLState.CONNECTION_FAILURE.getState(), e.getSQLState());
      assertEquals(-1, received.get(20, TimeUnit.SECONDS), "no startup packet must be sent");
    }
  }

  @Test
  void peerWithAlpnPostgresqlGetsStartupPacket() throws Exception {
    try (SSLServerSocket server = serverSocket(true)) {
      CompletableFuture<Integer> received = firstMessageLength(server);
      assertThrows(SQLException.class, () -> connect(server.getLocalPort()));
      assertTrue(received.get(20, TimeUnit.SECONDS) > 0, "startup packet must be sent");
    }
  }
}
