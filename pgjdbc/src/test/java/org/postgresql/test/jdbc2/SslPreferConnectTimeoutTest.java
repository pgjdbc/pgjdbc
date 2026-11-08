/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.SocketFactory;

/**
 * {@code sslmode=prefer} must not retry a host whose TCP {@code connect} timed out.
 * Falling back to plaintext after a connect timeout only repeats {@code Socket.connect}
 * against the same unreachable host, usually with 1 ms of leftover timeout.
 * A timeout while waiting for the SSLRequest response must still fall back, as #3968 intended.
 *
 * @see <a href="https://github.com/pgjdbc/pgjdbc/issues/4516">Issue #4516</a>
 */
@ResourceLock("SslPreferConnectTimeoutTest")
class SslPreferConnectTimeoutTest {
  // Documentation range; the socket factory never opens a real connection.
  private static final String URL = "jdbc:postgresql://192.0.2.1:5432/test";

  @BeforeEach
  void setUp() {
    TestUtil.initDriver();
    CountingSocketFactory.reset();
  }

  @Test
  void preferDoesNotRetryAfterConnectTimeout() {
    SQLException ex = connectAndFail("prefer", CountingSocketFactory.Mode.CONNECT_TIMEOUT);
    assertInstanceOf(SocketTimeoutException.class, ex.getCause());
    assertEquals(1, CountingSocketFactory.connectCount.get(),
        "sslmode=prefer should not open a second socket after Socket.connect times out");
  }

  @Test
  void disableConnectsOnceOnConnectTimeout() {
    SQLException ex = connectAndFail("disable", CountingSocketFactory.Mode.CONNECT_TIMEOUT);
    assertInstanceOf(SocketTimeoutException.class, ex.getCause());
    assertEquals(1, CountingSocketFactory.connectCount.get());
  }

  @Test
  void preferStillFallsBackAfterSslResponseTimeout() {
    SQLException ex = connectAndFail("prefer", CountingSocketFactory.Mode.SSL_RESPONSE_TIMEOUT);
    assertInstanceOf(SocketTimeoutException.class, ex.getCause());
    assertEquals(2, CountingSocketFactory.connectCount.get(),
        "sslmode=prefer should still retry without SSL when the SSLRequest response times out");
  }

  private static SQLException connectAndFail(String sslMode, CountingSocketFactory.Mode mode) {
    CountingSocketFactory.mode = mode;
    Properties props = new Properties();
    PGProperty.USER.set(props, "test");
    PGProperty.PASSWORD.set(props, "test");
    PGProperty.CONNECT_TIMEOUT.set(props, 5);
    PGProperty.SOCKET_FACTORY.set(props, CountingSocketFactory.class.getName());
    PGProperty.GSS_ENC_MODE.set(props, "disable");
    PGProperty.SSL_MODE.set(props, sslMode);
    return assertThrows(SQLException.class, () -> DriverManager.getConnection(URL, props));
  }

  /**
   * Stands in for an unreachable host or a server that never answers SSLRequest.
   * Instantiated by {@code ObjectFactory} from the {@code socketFactory} property.
   */
  public static class CountingSocketFactory extends SocketFactory {
    enum Mode {
      CONNECT_TIMEOUT,
      SSL_RESPONSE_TIMEOUT
    }

    static final AtomicInteger connectCount = new AtomicInteger();
    static volatile Mode mode = Mode.CONNECT_TIMEOUT;

    static void reset() {
      connectCount.set(0);
      mode = Mode.CONNECT_TIMEOUT;
    }

    @Override
    public Socket createSocket() {
      return new Socket() {
        private boolean connected;

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
          connectCount.incrementAndGet();
          if (mode == Mode.CONNECT_TIMEOUT) {
            throw new SocketTimeoutException("Connect timed out");
          }
          connected = true;
        }

        @Override
        public boolean isConnected() {
          return connected;
        }

        @Override
        public InputStream getInputStream() {
          // First connect: pretend the SSLRequest response timed out.
          // Later connects (the prefer fallback) return EOF so authentication
          // fails immediately instead of retrying a SocketTimeoutException
          // forever when no socketTimeout was requested.
          final boolean sslResponseTimeout = mode == Mode.SSL_RESPONSE_TIMEOUT
              && connectCount.get() == 1;
          return new InputStream() {
            @Override
            public int read() throws IOException {
              if (sslResponseTimeout) {
                throw new SocketTimeoutException("Read timed out");
              }
              return -1;
            }
          };
        }

        @Override
        public OutputStream getOutputStream() {
          return new ByteArrayOutputStream();
        }
      };
    }

    @Override
    public Socket createSocket(String host, int port) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) {
      throw new UnsupportedOperationException();
    }
  }
}
