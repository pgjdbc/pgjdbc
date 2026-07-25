/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import javax.net.SocketFactory;

/**
 * Verifies that the underlying socket is closed when {@code PgConnection} setup fails after the
 * network connection has already been established. The connection is opened successfully by
 * {@code ConnectionFactory.openConnection}, then a later setup step throws, so the socket must be
 * closed by the constructor's cleanup path rather than leaked.
 */
class ConnectionSetupFailureTest {

  /**
   * A {@link SocketFactory} that records every socket it hands out, so the test can assert the
   * sockets were closed. It is referenced by class name through the {@code socketFactory}
   * connection property and instantiated by {@code ObjectFactory} via its implicit no-argument
   * constructor.
   */
  public static class RecordingSocketFactory extends SocketFactory {
    static final List<Socket> SOCKETS = Collections.synchronizedList(new ArrayList<>());

    static void reset() {
      SOCKETS.clear();
    }

    private static Socket record(Socket socket) {
      SOCKETS.add(socket);
      return socket;
    }

    @Override
    public Socket createSocket() {
      return record(new Socket());
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
      return record(new Socket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
        throws IOException {
      return record(new Socket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
      return record(new Socket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
        int localPort) throws IOException {
      return record(new Socket(address, port, localAddress, localPort));
    }
  }

  @Test
  void socketClosedWhenSetupFailsAfterConnect() throws Exception {
    RecordingSocketFactory.reset();

    Properties props = new Properties();
    PGProperty.SOCKET_FACTORY.set(props, RecordingSocketFactory.class.getName());
    // An invalid stringtype value is validated only inside the PgConnection constructor, after the
    // socket has already been opened, so it triggers the post-openConnection cleanup path.
    PGProperty.STRING_TYPE.set(props, "this-is-not-a-valid-stringtype");

    SQLException ex = assertThrows(SQLException.class, () -> TestUtil.openDB(props));
    assertTrue(ex.getMessage().contains("stringtype"),
        "Expected a stringtype validation failure, but got: " + ex.getMessage());

    assertFalse(RecordingSocketFactory.SOCKETS.isEmpty(),
        "The connection attempt should have created at least one socket");
    for (Socket socket : RecordingSocketFactory.SOCKETS) {
      assertTrue(socket.isClosed(),
          "Socket should be closed after the failed connection setup: " + socket);
    }
  }
}
