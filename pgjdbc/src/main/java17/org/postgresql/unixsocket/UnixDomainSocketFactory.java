/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.unixsocket;

import org.postgresql.PGProperty;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Properties;

import javax.net.SocketFactory;

/**
 * A {@link SocketFactory} that connects to PostgreSQL over a Unix domain socket using
 * {@link java.net.UnixDomainSocketAddress} (Java 16+).
 *
 * <p>This is the Java 17 implementation that replaces the base stub when the driver runs on Java 17
 * or later (it ships under {@code META-INF/versions/17} in the multi-release JAR).</p>
 *
 * <p>Set the {@code socketFactory} connection property to
 * {@code org.postgresql.unixsocket.UnixDomainSocketFactory} and {@code socketFactoryArg} to the
 * directory that holds the server socket (for example {@code /var/run/postgresql}). The port from
 * the JDBC URL selects the socket file {@code .s.PGSQL.<port>} inside that directory. You may also
 * point {@code socketFactoryArg} directly at a socket file. The host part of the URL is ignored.</p>
 */
public class UnixDomainSocketFactory extends SocketFactory {

  private final String path;

  public UnixDomainSocketFactory(Properties info) {
    String arg = PGProperty.SOCKET_FACTORY_ARG.getOrDefault(info);
    if (arg == null || arg.isEmpty()) {
      arg = "/var/run/postgresql";
    }
    this.path = arg;
  }

  @Override
  public Socket createSocket() throws IOException {
    return new UnixDomainSocket(path);
  }

  @Override
  public Socket createSocket(String host, int port) throws IOException {
    Socket socket = createSocket();
    socket.connect(UnixDomainSocket.resolveAddress(path, port));
    return socket;
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
      throws IOException {
    return createSocket(host, port);
  }

  @Override
  public Socket createSocket(InetAddress host, int port) throws IOException {
    return createSocket(host.getHostName(), port);
  }

  @Override
  public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
      throws IOException {
    return createSocket(address.getHostName(), port);
  }
}
