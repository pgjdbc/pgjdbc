/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.SocketFactory;

public class CapturingSocketFactory extends SocketFactory {
  private static final AtomicReference<Properties> LAST_PROPERTIES = new AtomicReference<>();

  final Properties properties;

  public CapturingSocketFactory(Properties properties) {
    this.properties = properties;
    LAST_PROPERTIES.set(properties);
  }

  static void reset() {
    LAST_PROPERTIES.set(null);
  }

  static Properties getLastProperties() {
    return LAST_PROPERTIES.get();
  }

  @Override
  public Socket createSocket() {
    return new Socket();
  }

  @Override
  public Socket createSocket(String host, int port) throws IOException {
    return new Socket(host, port);
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
      throws IOException {
    return new Socket(host, port, localHost, localPort);
  }

  @Override
  public Socket createSocket(InetAddress host, int port) throws IOException {
    return new Socket(host, port);
  }

  @Override
  public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
      int localPort) throws IOException {
    return new Socket(address, port, localAddress, localPort);
  }
}
