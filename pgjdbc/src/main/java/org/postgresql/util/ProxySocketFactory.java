/*
 * Copyright (c) 2011, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

public class ProxySocketFactory extends javax.net.SocketFactory {

  private Proxy proxy;

  public ProxySocketFactory(Proxy proxy) {
    this.proxy = proxy;
  }


  public ProxySocketFactory(String urlString) throws URISyntaxException
  {
    URI url = new URI(urlString);

    Proxy.Type proxyType = Proxy.Type.DIRECT;

    if (url.getScheme().equalsIgnoreCase("http")) {
      proxyType = Proxy.Type.HTTP;
    }
    else if (url.getScheme().equalsIgnoreCase("socks")) {
      proxyType = Proxy.Type.SOCKS;
    }

    final String proxyHost = url.getHost();
    final int proxyPort = url.getPort();

    String userInfo = url.getUserInfo();

    if (userInfo != null ) {

      final String username = userInfo.split(":")[0];
      final String password = userInfo.split(":")[1];

      // Java ignores http.proxyUser. Here come's the workaround.
      Authenticator.setDefault(new Authenticator() {
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
          if (getRequestorType() == RequestorType.PROXY) {

            if (getRequestingHost().equalsIgnoreCase(proxyHost)) {
              if (proxyPort == getRequestingPort()) {
                // Seems to be OK.
                return new PasswordAuthentication(username, password.toCharArray());
              }
            }
          }
          return null;
        }
      });
    }

    this.proxy = new Proxy(proxyType, new InetSocketAddress(proxyHost, proxyPort));
  }

  public Proxy getProxy() {
    return this.proxy;
  }

  @Override
  public Socket createSocket() throws IOException {
    return new Socket(proxy);
  }

  @Override
  public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
    Socket socket = createSocket();
    socket.connect(InetSocketAddress.createUnresolved(host, port));
    return socket;

  }

  @Override
  public Socket createSocket(InetAddress host, int port) throws IOException {
    Socket socket = createSocket();
    socket.connect(new InetSocketAddress(host, port));
    return socket;
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localhost, int localport)
      throws IOException, UnknownHostException {
    Socket socket = createSocket();
    socket.bind(new InetSocketAddress(localhost, localport));
    socket.connect(InetSocketAddress.createUnresolved(host, port));
    return socket;
  }

  @Override
  public Socket createSocket(InetAddress host, int port, InetAddress localhost, int localport) throws IOException {
    // TODO Auto-generated method stub
    Socket socket = createSocket();
    socket.bind(new InetSocketAddress(localhost, localport));
    socket.connect(new InetSocketAddress(host, port));
    return socket;
  }

}
