/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.postgresql.PGProperty;
import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A connection opened after the server answers an encryption request with {@code E}, or after
 * GSS encryption fails under {@code gssEncMode=prefer}, carries the same stream settings as a
 * connection that never sent one: {@code maxResultBuffer}, the socket timeout,
 * {@code tcpKeepAlive}, {@code tcpNoDelay}, and the socket buffer sizes.
 *
 * <p>The driver closes the first connection in these cases and opens a new one. The new
 * connection used to start with {@code maxResultBuffer} unset, and after an {@code E} to
 * GSSENCRequest also with the default keep-alive, {@code TCP_NODELAY}, and socket buffer
 * sizes.</p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ReconnectAfterEncryptionErrorTest {
  private static final int SSL_REQUEST = 80877103;
  private static final int GSS_ENC_REQUEST = 80877104;

  static Stream<Arguments> encryptionRequests() {
    return Stream.of(
        argumentSet("SSLRequest answered with E", "prefer", "disable", 'E'),
        argumentSet("GSSENCRequest answered with E", "disable", "prefer", 'E'),
        // The backend closes the connection after G, so the GSS handshake fails.
        argumentSet("GSSENCRequest answered with G", "disable", "prefer", 'G'));
  }

  @ParameterizedTest
  @MethodSource("encryptionRequests")
  void theNewConnectionKeepsTheStreamSettings(String sslMode, String gssEncMode,
      char gssEncResponse) throws Exception {
    StreamSettings plain;
    StreamSettings reconnected;
    try (Backend backend = new Backend(gssEncResponse)) {
      plain = connect(backend, "disable", "disable");
      reconnected = connect(backend, sslMode, gssEncMode);
    }

    assertAll(
        () -> assertEquals(12345, reconnected.maxResultBuffer, "maxResultBuffer"),
        () -> assertEquals(10000, reconnected.soTimeout, "SO_TIMEOUT"),
        () -> assertEquals(true, reconnected.keepAlive, "SO_KEEPALIVE"),
        () -> assertEquals(false, reconnected.tcpNoDelay, "TCP_NODELAY"),
        // The OS may round a requested buffer size, so the expected value is what it reports for
        // the connection that never reconnected.
        () -> assertEquals(plain.receiveBufferSize, reconnected.receiveBufferSize, "SO_RCVBUF"),
        () -> assertEquals(plain.sendBufferSize, reconnected.sendBufferSize, "SO_SNDBUF"));
  }

  private static class StreamSettings {
    final long maxResultBuffer;
    final int soTimeout;
    final boolean keepAlive;
    final boolean tcpNoDelay;
    final int receiveBufferSize;
    final int sendBufferSize;

    StreamSettings(PGStream stream) throws IOException {
      Socket socket = stream.getSocket();
      maxResultBuffer = stream.getMaxResultBuffer();
      soTimeout = socket.getSoTimeout();
      keepAlive = socket.getKeepAlive();
      tcpNoDelay = socket.getTcpNoDelay();
      receiveBufferSize = socket.getReceiveBufferSize();
      sendBufferSize = socket.getSendBufferSize();
    }
  }

  /**
   * Connects to the backend with the stream settings the assertions expect, records them, and
   * closes the connection.
   */
  private static StreamSettings connect(Backend backend, String sslMode, String gssEncMode)
      throws SQLException, IOException {
    Properties info = new Properties();
    PGProperty.USER.set(info, "test");
    PGProperty.PG_DBNAME.set(info, "test");
    PGProperty.PROTOCOL_VERSION.set(info, "3");
    // The startup packet then carries every parameter, and the driver sends no setup query.
    PGProperty.ASSUME_MIN_SERVER_VERSION.set(info, "9.0");
    PGProperty.SSL_MODE.set(info, sslMode);
    PGProperty.GSS_ENC_MODE.set(info, gssEncMode);
    // GSS then reads the default credentials and makes no JAAS login.
    PGProperty.JAAS_LOGIN.set(info, false);
    PGProperty.CONNECT_TIMEOUT.set(info, 10);
    PGProperty.SOCKET_TIMEOUT.set(info, 10);
    PGProperty.MAX_RESULT_BUFFER.set(info, "12345");
    PGProperty.TCP_KEEP_ALIVE.set(info, true);
    PGProperty.TCP_NO_DELAY.set(info, false);
    PGProperty.RECEIVE_BUFFER_SIZE.set(info, 32768);
    PGProperty.SEND_BUFFER_SIZE.set(info, 65536);
    QueryExecutor executor = ConnectionFactory.openConnection(
        new HostSpec[]{new HostSpec("127.0.0.1", backend.getPort())}, info);
    try {
      return new StreamSettings(((QueryExecutorBase) executor).pgStream);
    } finally {
      executor.close();
    }
  }

  /**
   * Answers SSLRequest with {@code E} and GSSENCRequest with the given byte, then closes the
   * connection. Answers a startup packet with AuthenticationOk and ReadyForQuery.
   */
  private static class Backend implements Closeable, Runnable {
    private final ServerSocket serverSocket;
    private final char gssEncResponse;
    private volatile boolean closed;

    Backend(char gssEncResponse) throws IOException {
      this.gssEncResponse = gssEncResponse;
      serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
      Thread thread = new Thread(this, "encryption-error-backend");
      thread.setDaemon(true);
      thread.start();
    }

    int getPort() {
      return serverSocket.getLocalPort();
    }

    @Override
    public void run() {
      while (!closed) {
        try (Socket socket = serverSocket.accept()) {
          socket.setSoTimeout(30000);
          InputStream in = socket.getInputStream();
          OutputStream out = socket.getOutputStream();
          int length = readInt4(in);
          byte[] body = readFully(in, length - 4);
          int code = length == 8 ? ((body[0] & 0xFF) << 24) | ((body[1] & 0xFF) << 16)
              | ((body[2] & 0xFF) << 8) | (body[3] & 0xFF) : 0;
          if (code == SSL_REQUEST || code == GSS_ENC_REQUEST) {
            out.write(code == SSL_REQUEST ? 'E' : gssEncResponse);
            out.flush();
            continue;
          }
          out.write(startupResponse());
          out.flush();
          while (in.read() >= 0) {
            // Wait for the driver to close from its end.
          }
        } catch (SocketException e) {
          // close() ends accept().
        } catch (IOException e) {
          // The driver hung up mid-exchange; wait for the next connection.
        }
      }
    }

    private static byte[] startupResponse() throws IOException {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      writeMessage(out, 'R', new byte[]{0, 0, 0, 0});
      writeParameterStatus(out, "server_version", "17.0");
      writeParameterStatus(out, "client_encoding", "UTF8");
      writeParameterStatus(out, "DateStyle", "ISO, MDY");
      writeParameterStatus(out, "integer_datetimes", "on");
      writeParameterStatus(out, "standard_conforming_strings", "on");
      writeParameterStatus(out, "TimeZone", "UTC");
      writeMessage(out, 'K', new byte[]{0, 0, 0, 1, 0, 0, 0, 2});
      writeMessage(out, 'Z', new byte[]{'I'});
      return out.toByteArray();
    }

    private static void writeParameterStatus(ByteArrayOutputStream out, String name, String value)
        throws IOException {
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      body.write(name.getBytes(StandardCharsets.UTF_8));
      body.write(0);
      body.write(value.getBytes(StandardCharsets.UTF_8));
      body.write(0);
      writeMessage(out, 'S', body.toByteArray());
    }

    private static void writeMessage(ByteArrayOutputStream out, char type, byte[] body)
        throws IOException {
      int length = body.length + 4;
      out.write(type);
      out.write(length >>> 24);
      out.write(length >>> 16);
      out.write(length >>> 8);
      out.write(length);
      out.write(body);
    }

    private static int readInt4(InputStream in) throws IOException {
      byte[] bytes = readFully(in, 4);
      return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
          | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
      byte[] bytes = new byte[length];
      for (int i = 0; i < length; i++) {
        int b = in.read();
        if (b < 0) {
          throw new IOException("end of stream after " + i + " of " + length + " bytes");
        }
        bytes[i] = (byte) b;
      }
      return bytes;
    }

    @Override
    public void close() {
      closed = true;
      try {
        serverSocket.close();
      } catch (IOException e) {
        // Nothing to do; the thread ends on the next accept().
      }
    }
  }
}
