/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGProperty;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.SocketFactory;

/**
 * A requireAuth value that excludes every method must refuse the connection
 * with {@link PSQLState#CONNECTION_REJECTED}, whatever the server asks for,
 * and the driver must not send the password.
 */
public class RequireAuthConnectTest {

  /** AuthenticationCleartextPassword: the server asks for the password. */
  private static final byte[] CLEARTEXT_PASSWORD_REQUEST =
      {'R', 0, 0, 0, 8, 0, 0, 0, 3};

  /** AuthenticationOk sent before any request: trust authentication. */
  private static final byte[] AUTHENTICATION_OK =
      {'R', 0, 0, 0, 8, 0, 0, 0, 0};

  private static final String PASSWORD = "secret";

  /** The PasswordMessage that carries {@link #PASSWORD}. */
  private static final byte[] PASSWORD_MESSAGE =
      {'p', 0, 0, 0, 11, 's', 'e', 'c', 'r', 'e', 't', 0};

  @Test
  void refusesACleartextPasswordRequest() {
    FakeServer server = new FakeServer(CLEARTEXT_PASSWORD_REQUEST);
    SQLException e = assertThrows(SQLException.class,
        () -> server.connect(RequireAuthValues.EVERY_METHOD_EXCLUDED));
    // The bytes are checked before the SQLState, so if the driver sends the
    // password, the test fails on the password and not on the error that
    // follows it.
    assertArrayEquals(new byte[0], server.writtenAfterStartup(),
        "bytes the driver wrote after the startup message");
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), e.getSQLState(),
        "SQLState of the refusal");
  }

  /**
   * A server that sends the same request gets a PasswordMessage when
   * requireAuth allows the password. Without this test, the empty result in
   * {@link #refusesACleartextPasswordRequest} could come from a socket that
   * stopped recording.
   */
  @Test
  void sendsThePasswordWhenRequireAuthAllowsIt() {
    FakeServer server = new FakeServer(CLEARTEXT_PASSWORD_REQUEST);
    // The reply ends after the request, so the driver fails at end of
    // stream once it has sent the password. This test does not check that
    // failure.
    assertThrows(SQLException.class, () -> server.connect("password"));
    assertArrayEquals(PASSWORD_MESSAGE, server.writtenAfterStartup(),
        "bytes the driver wrote after the startup message");
  }

  /**
   * requireAuth calls trust authentication "none", and
   * {@link RequireAuthValues#EVERY_METHOD_EXCLUDED} excludes "none" too.
   */
  @Test
  void refusesTrust() {
    FakeServer server = new FakeServer(AUTHENTICATION_OK);
    SQLException e = assertThrows(SQLException.class,
        () -> server.connect(RequireAuthValues.EVERY_METHOD_EXCLUDED));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), e.getSQLState(),
        "SQLState of the refusal");
  }

  /** Replies with a fixed message and records what the driver writes. */
  private static final class FakeServer {
    /**
     * The driver creates the socket factory from its class name and passes
     * it only string properties. So the factory finds the server by the key
     * that {@link #connect} passes as socketFactoryArg.
     */
    static final Map<String, FakeServer> BY_KEY = new ConcurrentHashMap<>();

    final byte[] reply;
    final ByteArrayOutputStream written = new ByteArrayOutputStream();

    FakeServer(byte[] reply) {
      this.reply = reply;
    }

    void connect(String requireAuth) throws SQLException {
      String key = UUID.randomUUID().toString();
      Properties props = new Properties();
      PGProperty.USER.set(props, "test");
      PGProperty.PASSWORD.set(props, PASSWORD);
      PGProperty.SSL_MODE.set(props, "disable");
      PGProperty.GSS_ENC_MODE.set(props, "disable");
      PGProperty.REQUIRE_AUTH.set(props, requireAuth);
      PGProperty.SOCKET_FACTORY.set(props,
          FakeServerSocketFactory.class.getName());
      PGProperty.SOCKET_FACTORY_ARG.set(props, key);
      BY_KEY.put(key, this);
      try {
        // The factory ignores the host. The driver records the failure for the
        // host in the JVM-wide GlobalHostStatusTracker, which multi-host URLs
        // read, so the host is a name that cannot exist.
        DriverManager.getConnection(
            "jdbc:postgresql://requireauth.invalid/test", props).close();
      } finally {
        BY_KEY.remove(key);
      }
    }

    /**
     * Returns what the driver wrote after its startup message, which starts
     * with its length.
     */
    byte[] writtenAfterStartup() {
      byte[] bytes = written.toByteArray();
      int startupLength = ByteBuffer.wrap(bytes).getInt();
      return Arrays.copyOfRange(bytes, startupLength, bytes.length);
    }
  }

  /**
   * Creates sockets that read {@link FakeServer#reply} and write to
   * {@link FakeServer#written}. The driver loads this class through the
   * socketFactory property and calls only {@link #createSocket()}.
   */
  public static class FakeServerSocketFactory extends SocketFactory {
    private final FakeServer server;

    public FakeServerSocketFactory(String key) {
      this.server = FakeServer.BY_KEY.get(key);
    }

    @Override
    public Socket createSocket() {
      return new Socket() {
        private final InputStream in = new ByteArrayInputStream(server.reply);

        /** Reports connected, so the driver does not connect it. */
        @Override
        public boolean isConnected() {
          return true;
        }

        @Override
        public InputStream getInputStream() {
          return in;
        }

        @Override
        public OutputStream getOutputStream() {
          return server.written;
        }
      };
    }

    @Override
    public Socket createSocket(String host, int port) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost,
        int localPort) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket(InetAddress address, int port,
        InetAddress localAddress, int localPort) {
      throw new UnsupportedOperationException();
    }
  }
}
