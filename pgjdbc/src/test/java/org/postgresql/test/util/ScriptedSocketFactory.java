/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import java.net.InetAddress;
import java.net.Socket;

import javax.net.SocketFactory;

/**
 * A {@code SocketFactory} the driver can instantiate from the {@code socketFactory}
 * connection property, serving a canned backend response set by {@link #setScript(byte[])}.
 *
 * <p>The driver writes a request and then reads the reply, so a byte script that ignores
 * what was written is enough to drive the connection handshake off a fixed array. Nothing
 * here talks to the network.</p>
 *
 * <p>The script is per-thread, so tests that share a JVM do not have to run in isolation.
 * A test sets the script, opens the connection, and reads back what the driver wrote with
 * {@link #getSentBytes()}.</p>
 */
public class ScriptedSocketFactory extends SocketFactory {
  private static final ThreadLocal<byte[]> SCRIPT = new ThreadLocal<>();
  private static final ThreadLocal<InMemorySocketFactory> LAST_FACTORY = new ThreadLocal<>();

  /** Name to pass as the {@code socketFactory} connection property. */
  public static final String CLASS_NAME = ScriptedSocketFactory.class.getName();

  private final InMemorySocketFactory delegate;

  public ScriptedSocketFactory() {
    byte[] script = SCRIPT.get();
    if (script == null) {
      throw new IllegalStateException(
          "No script set for " + CLASS_NAME + ". Call setScript before connecting.");
    }
    delegate = new InMemorySocketFactory(script);
    LAST_FACTORY.set(delegate);
  }

  /**
   * Sets the bytes the backend will send, for connections opened on this thread. Drops the
   * traffic captured from an earlier connection on this thread.
   */
  public static void setScript(byte[] script) {
    SCRIPT.set(script);
    LAST_FACTORY.remove();
  }

  /** Forgets the script and the captured traffic. */
  public static void clear() {
    SCRIPT.remove();
    LAST_FACTORY.remove();
  }

  /** Everything the driver wrote to the most recent socket opened on this thread. */
  public static byte[] getSentBytes() {
    InMemorySocketFactory factory = LAST_FACTORY.get();
    if (factory == null) {
      throw new IllegalStateException("No connection has been opened on this thread");
    }
    return factory.getSentBytes();
  }

  @Override
  public Socket createSocket() {
    return delegate.createSocket();
  }

  @Override
  public Socket createSocket(String host, int port) {
    return delegate.createSocket(host, port);
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
    return delegate.createSocket(host, port, localHost, localPort);
  }

  @Override
  public Socket createSocket(InetAddress host, int port) {
    return delegate.createSocket(host, port);
  }

  @Override
  public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
      int localPort) {
    return delegate.createSocket(address, port, localAddress, localPort);
  }
}
