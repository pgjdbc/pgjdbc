/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

import javax.net.SocketFactory;

/**
 * Hands the driver unconnected {@link SocketRejectingOptions}. The driver only calls
 * {@link #createSocket()}.
 */
public class SocketRejectingOptionsFactory extends SocketFactory {

  @Override
  public Socket createSocket() {
    return new SocketRejectingOptions();
  }

  @Override
  public Socket createSocket(String host, int port) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(InetAddress host, int port) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
      int localPort) throws IOException {
    throw new UnsupportedOperationException();
  }
}
