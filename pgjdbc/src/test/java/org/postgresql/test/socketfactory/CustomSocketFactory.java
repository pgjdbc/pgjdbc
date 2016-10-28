/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.socketfactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import javax.net.SocketFactory;

public class CustomSocketFactory extends SocketFactory {

  private static CustomSocketFactory instance;

  private final String argument;
  private int socketCreated;

  public CustomSocketFactory(String argument) {
    if (instance != null) {
      throw new IllegalStateException("Test failed, multiple custom socket factory instanciation");
    }
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

  public static CustomSocketFactory getInstance() {
    return instance;
  }

}
