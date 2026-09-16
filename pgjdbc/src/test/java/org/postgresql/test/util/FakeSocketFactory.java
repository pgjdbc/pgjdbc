/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import java.net.InetAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.net.SocketFactory;

/**
 * Returns sockets the test supplies, from every {@code createSocket} overload.
 *
 * <p>A test that builds the factory itself passes the socket to
 * {@link #FakeSocketFactory(Socket)}. A test that goes through the driver registers a supplier
 * with {@link #register(String, Supplier)} and passes the key in the {@code socketFactoryArg}
 * connection property; the driver then instantiates this class by name through
 * {@link #FakeSocketFactory(String)}.</p>
 */
public final class FakeSocketFactory extends SocketFactory {
  private static final Map<String, Supplier<? extends Socket>> SUPPLIERS =
      new ConcurrentHashMap<>();

  private final Supplier<? extends Socket> sockets;

  /** Returns {@code socket} from every call. */
  public FakeSocketFactory(Socket socket) {
    this.sockets = () -> socket;
  }

  /**
   * Returns the sockets of the supplier registered under {@code key}.
   *
   * @throws IllegalStateException if no supplier is registered under {@code key}
   */
  public FakeSocketFactory(String key) {
    Supplier<? extends Socket> supplier = SUPPLIERS.get(key);
    if (supplier == null) {
      throw new IllegalStateException("no socket supplier registered for " + key);
    }
    this.sockets = supplier;
  }

  public static void register(String key, Supplier<? extends Socket> supplier) {
    SUPPLIERS.put(key, supplier);
  }

  public static void unregister(String key) {
    SUPPLIERS.remove(key);
  }

  @Override
  public Socket createSocket() {
    return sockets.get();
  }

  @Override
  public Socket createSocket(String host, int port) {
    return sockets.get();
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
    return sockets.get();
  }

  @Override
  public Socket createSocket(InetAddress host, int port) {
    return sockets.get();
  }

  @Override
  public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
      int localPort) {
    return sockets.get();
  }
}
