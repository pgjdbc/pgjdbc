/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.unixsocket;

import org.postgresql.util.GT;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Properties;

import javax.net.SocketFactory;

/**
 * A {@link SocketFactory} that connects to PostgreSQL over a Unix domain socket.
 *
 * <p>The implementation relies on {@link java.net.UnixDomainSocketAddress}, which is only available
 * on Java 16 and later. The driver ships as a multi-release JAR: this base class is a stub that
 * fails fast, and the real implementation lives in {@code META-INF/versions/17} and is selected
 * automatically when the driver runs on Java 17 or later.</p>
 *
 * <p>To use it, set the {@code socketFactory} connection property to
 * {@code org.postgresql.unixsocket.UnixDomainSocketFactory} and {@code socketFactoryArg} to the
 * directory that holds the server socket (for example {@code /var/run/postgresql}). The driver
 * appends {@code .s.PGSQL.<port>} to that directory, so the port from the JDBC URL still selects the
 * socket file. The host part of the URL is ignored, so {@code localhost} is a fine placeholder.</p>
 */
public class UnixDomainSocketFactory extends SocketFactory {

  public UnixDomainSocketFactory(Properties info) {
    throw unsupported();
  }

  @Override
  public Socket createSocket() throws IOException {
    throw unsupported();
  }

  @Override
  public Socket createSocket(String host, int port) throws IOException {
    throw unsupported();
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
      throws IOException {
    throw unsupported();
  }

  @Override
  public Socket createSocket(InetAddress host, int port) throws IOException {
    throw unsupported();
  }

  @Override
  public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
      throws IOException {
    throw unsupported();
  }

  private static UnsupportedOperationException unsupported() {
    return new UnsupportedOperationException(
        GT.tr("Unix domain socket connections require Java 17 or later. "
            + "Run the driver on Java 17 or newer to use "
            + "org.postgresql.unixsocket.UnixDomainSocketFactory."));
  }
}
