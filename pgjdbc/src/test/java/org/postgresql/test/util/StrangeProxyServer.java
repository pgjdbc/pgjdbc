/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import org.postgresql.test.TestUtil;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Proxy server that allows for pretending that traffic did not arrive at the
 * destination. Client connections created prior to a call to
 * stopForwardingOlderClients() will not have any subsequent traffic forwarded.
 * Bytes are transferred one-by-one. If either side of the connection reaches
 * EOF then both sides are immediately closed.
 */
public class StrangeProxyServer implements Closeable {
  private final ServerSocket serverSock;
  private volatile boolean keepRunning = true;
  private volatile long minAcceptedAt = 0;

  public StrangeProxyServer(String destHost, int destPort) throws IOException {
    this.serverSock = new ServerSocket(0);
    this.serverSock.setSoTimeout(100);
    doAsync(() -> {
      while (keepRunning) {
        try {
          Socket sourceSock = serverSock.accept();
          final long acceptedAt = System.currentTimeMillis();
          Socket destSock = new Socket(destHost, destPort);
          doAsync(() -> transferOneByOne(acceptedAt, sourceSock, destSock));
          doAsync(() -> transferOneByOne(acceptedAt, destSock, sourceSock));
        } catch (SocketTimeoutException ignore) {
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
      TestUtil.closeQuietly(serverSock);
    });
  }

  public int getServerPort() {
    return this.serverSock.getLocalPort();
  }

  @Override
  public void close() {
    this.keepRunning = false;
  }

  public void stopForwardingOlderClients() {
    this.minAcceptedAt = System.currentTimeMillis();
  }

  public void stopForwardingAllClients() {
    this.minAcceptedAt = Long.MAX_VALUE;
  }

  private void doAsync(Runnable task) {
    Thread thread = new Thread(task);
    thread.setDaemon(true);
    thread.start();
  }

  private void transferOneByOne(long acceptedAt, Socket source, Socket dest) {
    try {
      InputStream in = source.getInputStream();
      OutputStream out = dest.getOutputStream();
      int b;
      // As long as we're running try to read
      while (keepRunning && (b = in.read()) >= 0) {
        // But only write it if the client is newer than the last call to stopForwardingOlderClients()
        if (acceptedAt >= minAcceptedAt) {
          out.write(b);
        }
      }
    } catch (IOException ignore) {
    } finally {
      TestUtil.closeQuietly(source);
      TestUtil.closeQuietly(dest);
    }
  }
}
