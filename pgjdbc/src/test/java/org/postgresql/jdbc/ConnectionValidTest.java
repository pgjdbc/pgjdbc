/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.DisabledIfServerVersionBelow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@DisabledIfServerVersionBelow("9.4")
class ConnectionValidTest {
  private Connection connection;

  private ConnectionBreaker connectionBreaker;

  @BeforeEach
  void setUp() throws Exception {
    final Properties props = new Properties();
    connectionBreaker = new ConnectionBreaker(
        TestUtil.getServer(),
        TestUtil.getPort());
    connectionBreaker.acceptAsyncConnection();

    TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, connectionBreaker.getListenHost());
    TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT, String.valueOf(connectionBreaker.getListenPort()));

    connection = TestUtil.openDB(props);
  }

  @AfterEach
  void tearDown() throws Exception {
    connectionBreaker.close();
    connection.close();
  }

  /**
   * Tests if a connection is valid within 5 seconds.
   * @throws Exception if a database exception occurs.
   */
  @Test
  @Timeout(30)
  void isValid() throws Exception {
    connectionBreaker.breakConnection();
    boolean result = connection.isValid(5);

    assertThat("Is connection valid?",
        result,
        equalTo(false)
    );
  }

  private static final class ConnectionBreaker {

    private final ExecutorService workers;

    private final ServerSocket internalServer;

    private final Socket pgSocket;

    private volatile boolean breakConnection;

    /**
     * Constructor of the forwarder for the PostgreSQL server.
     *
     * @param pgServer   The PostgreSQL server address.
     * @param pgPort     The PostgreSQL server port.
     * @throws Exception if anything goes wrong binding the server.
     */
    ConnectionBreaker(final String pgServer, final int pgPort) throws Exception {
      workers = Executors.newCachedThreadPool();
      internalServer = new ServerSocket(9009);
      pgSocket = new Socket(pgServer, pgPort);
      breakConnection = false;
    }

    public String getListenHost() {
      return internalServer.getInetAddress().getHostAddress();
    }

    public int getListenPort() {
      return internalServer.getLocalPort();
    }

    /**
     * Starts to accept a asynchronous connection.
     *
     * @throws Exception if something goes wrong with the sockets.
     */
    public void acceptAsyncConnection() throws Exception {
      final InputStream pgServerInputStream = pgSocket.getInputStream();
      final OutputStream pgServerOutputStream = pgSocket.getOutputStream();

      // Future socket;
      final Future<Socket> futureConnection = workers.submit(internalServer::accept);

      // Forward reads;
      workers.submit(() -> {
        while (!breakConnection) {
          final Socket conn = futureConnection.get();
          int read = pgServerInputStream.read();
          conn.getOutputStream().write(read);
        }
        return null;
      });

      // Forwards writes;
      workers.submit(() -> {
        while (!breakConnection) {
          final Socket conn = futureConnection.get();
          int read = conn.getInputStream().read();
          pgServerOutputStream.write(read);
        }
        return null;
      });
    }

    /**
     * Breaks the forwarding.
     */
    public void breakConnection() {
      this.breakConnection = true;
    }

    /**
     * Closes the sockets.
     */
    public void close() throws Exception {
      this.workers.shutdown();
      this.workers.awaitTermination(5, TimeUnit.SECONDS);
      this.internalServer.close();
      this.pgSocket.close();
    }

  }
}
