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
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.SocketFactory;

/**
 * A NUL in a startup parameter ends that value early and makes the rest further parameters. The
 * driver sends user first, so a NUL in the database name or currentSchema could set the role.
 */
public class StartupParamNulTest {

  /** AuthenticationOk, then ReadyForQuery. */
  private static final byte[] TRUST_REPLY = {
      'R', 0, 0, 0, 8, 0, 0, 0, 0,
      'Z', 0, 0, 0, 5, 'I',
  };

  @Test
  void refusesANulInTheDatabaseName() {
    assertRefused(props -> PGProperty.PG_DBNAME.set(props, "test\0user\0postgres"));
  }

  @Test
  void refusesANulInCurrentSchema() {
    assertRefused(props -> PGProperty.CURRENT_SCHEMA.set(props, "public\0user\0postgres"));
  }

  @Test
  void refusesANulInOptions() {
    assertRefused(props -> PGProperty.OPTIONS.set(props, "-c a=b\0replication\0database"));
  }

  @Test
  void refusesANulInTheUser() {
    assertRefused(props -> PGProperty.USER.set(props, "test\0user\0postgres"));
  }

  @Test
  void refusesANulInApplicationName() {
    assertRefused(props -> {
      PGProperty.ASSUME_MIN_SERVER_VERSION.set(props, "9.0");
      PGProperty.APPLICATION_NAME.set(props, "app\0user\0postgres");
    });
  }

  /** Without this, the empty results above could come from a socket that never recorded. */
  @Test
  void sendsTheStartupMessageWithoutANul() {
    FakeServer server = new FakeServer();
    // Still fails, at end of stream rather than on the parameter.
    assertThrows(SQLException.class,
        () -> server.connect(props -> PGProperty.PG_DBNAME.set(props, "test")));
    byte[] written = server.written.toByteArray();
    assertEquals(written.length, ByteBuffer.wrap(written).getInt(),
        "startup message only");
  }

  private static void assertRefused(Configurer configurer) {
    FakeServer server = new FakeServer();
    SQLException e = assertThrows(SQLException.class, () -> server.connect(configurer));
    // Bytes first, so sending the message fails here rather than on the error that follows.
    assertArrayEquals(new byte[0], server.written.toByteArray(),
        "bytes the driver wrote");
    assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState(),
        "SQLState of the refusal");
  }

  private interface Configurer {
    void configure(Properties props);
  }

  /** Replies with {@link #TRUST_REPLY} and records what the driver writes. */
  private static final class FakeServer {
    /** The factory is built from a class name, so socketFactoryArg carries the key to it. */
    static final Map<String, FakeServer> BY_KEY = new ConcurrentHashMap<>();

    final ByteArrayOutputStream written = new ByteArrayOutputStream();

    void connect(Configurer configurer) throws SQLException {
      String key = UUID.randomUUID().toString();
      Properties props = new Properties();
      PGProperty.USER.set(props, "test");
      PGProperty.PG_DBNAME.set(props, "test");
      PGProperty.PASSWORD.set(props, "secret");
      PGProperty.SSL_MODE.set(props, "disable");
      PGProperty.GSS_ENC_MODE.set(props, "disable");
      PGProperty.SOCKET_FACTORY.set(props, FakeServerSocketFactory.class.getName());
      PGProperty.SOCKET_FACTORY_ARG.set(props, key);
      configurer.configure(props);
      BY_KEY.put(key, this);
      try {
        // The host is unused but is recorded in the JVM-wide GlobalHostStatusTracker, so it
        // must be a name that cannot exist.
        DriverManager.getConnection("jdbc:postgresql://startupparam.invalid/", props).close();
      } finally {
        BY_KEY.remove(key);
      }
    }
  }

  /** Reads {@link #TRUST_REPLY} and writes to {@link FakeServer#written}. */
  public static class FakeServerSocketFactory extends SocketFactory {
    private final FakeServer server;

    public FakeServerSocketFactory(String key) {
      this.server = FakeServer.BY_KEY.get(key);
    }

    @Override
    public Socket createSocket() {
      return new Socket() {
        private final InputStream in = new ByteArrayInputStream(TRUST_REPLY);

        /** Returns true so the driver does not connect the socket. */
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
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) {
      throw new UnsupportedOperationException();
    }
  }
}
