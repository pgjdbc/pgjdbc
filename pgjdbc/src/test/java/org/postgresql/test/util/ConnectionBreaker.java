/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ConnectionBreaker {

  private final ExecutorService workers;

  private final ServerSocket internalServer;

  private final Socket pgSocket;

  private final int serverPort;

  private volatile boolean breakConnection;

  /**
   * Constructor of the forwarder for the PostgreSQL server.
   *
   * @param pgServer   The PostgreSQL server address.
   * @param pgPort     The PostgreSQL server port.
   * @throws Exception if anything goes wrong binding the server.
   */
  public ConnectionBreaker(final String pgServer, final int pgPort) throws Exception {
    workers = Executors.newCachedThreadPool();
    internalServer = new ServerSocket(0);
    serverPort = internalServer.getLocalPort();
    pgSocket = new Socket(pgServer, pgPort);
    breakConnection = false;
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

  public int getServerPort() {
    return serverPort;
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
    this.workers.shutdownNow();
    this.internalServer.close();
    this.pgSocket.close();
  }
}
