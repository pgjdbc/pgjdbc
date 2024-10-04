/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.socketfactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import javax.net.SocketFactory;

public class ClassLoaderCustomSocketFactory extends SocketFactory {

  private final String argument;
  private int socketCreated;
  private static ClassLoaderCustomSocketFactory instance;

  public ClassLoaderCustomSocketFactory(String argument) {
    if (Holder.customSocketFactory != null) {
      throw new IllegalStateException("Test failed, multiple custom socket factory instanciation");
    }
    Holder.customSocketFactory = this;
    instance = this;
    this.argument = argument;
  }

  @Override
  public Socket createSocket(String arg0, int arg1) throws IOException, UnknownHostException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(InetAddress arg0, int arg1) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(String arg0, int arg1, InetAddress arg2, int arg3)
      throws IOException, UnknownHostException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(InetAddress arg0, int arg1, InetAddress arg2, int arg3)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket() throws IOException {
    socketCreated++;
    return new Socket();
  }

  public String getArgument() {
    return argument;
  }

  public int getSocketCreated() {
    return socketCreated;
  }

  public static ClassLoaderCustomSocketFactory getInstance() {
    return instance;
  }

}
