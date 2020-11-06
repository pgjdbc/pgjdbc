/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

import javax.net.ssl.SSLSocketFactory;

/**
 * Provide a wrapper to a real SSLSocketFactory delegating all calls to the contained instance. A
 * subclass needs only provide a constructor for the wrapped SSLSocketFactory.
 */
public abstract class WrappedFactory extends SSLSocketFactory {

  // The field is indeed not initialized in this class, however it is a part of public API,
  // so it is hard to fix.
  @SuppressWarnings("initialization.fields.uninitialized")
  protected SSLSocketFactory factory;

  public Socket createSocket(InetAddress host, int port) throws IOException {
    return factory.createSocket(host, port);
  }

  public Socket createSocket(String host, int port) throws IOException {
    return factory.createSocket(host, port);
  }

  public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
      throws IOException {
    return factory.createSocket(host, port, localHost, localPort);
  }

  public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
      throws IOException {
    return factory.createSocket(address, port, localAddress, localPort);
  }

  public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
      throws IOException {
    return factory.createSocket(socket, host, port, autoClose);
  }

  public String[] getDefaultCipherSuites() {
    return factory.getDefaultCipherSuites();
  }

  public String[] getSupportedCipherSuites() {
    return factory.getSupportedCipherSuites();
  }
}
