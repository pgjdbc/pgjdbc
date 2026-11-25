/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.net.SocketFactory;

/**
 * Creates sockets that never connect. {@link Socket#connect(SocketAddress, int)} records the
 * timeout in {@link #TIMEOUTS}, sleeps for that many milliseconds, and throws
 * {@link SocketTimeoutException}, as a connect to a host that does not respond does. The sleep
 * makes each attempt use up its timeout, so a timeout the driver does not restart for each host
 * leaves the next host only what remains of it, and the test sees the shortened value.
 */
public class RecordSleepAndTimeoutSocketFactory extends SocketFactory {
  /**
   * Timeouts, in milliseconds, passed to each connect call, keyed by {@code host:port} with the host
   * as given in the URL ({@link InetSocketAddress#getHostString()}). Shared by all tests that use
   * this factory; clear it before each test.
   */
  public static final Map<String, List<Integer>> TIMEOUTS = new HashMap<>();

  public RecordSleepAndTimeoutSocketFactory(@Nullable Properties ignored) {
    super();
  }

  @Override
  public Socket createSocket() {
    return new RecordSleepAndTimeoutSocket();
  }

  @Override
  public Socket createSocket(String s, int i) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(String s, int i, InetAddress inetAddress, int i1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(InetAddress inetAddress, int i) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(InetAddress inetAddress, int i, InetAddress inetAddress1, int i1) {
    throw new UnsupportedOperationException();
  }

  private static class RecordSleepAndTimeoutSocket extends Socket {
    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
      if (endpoint instanceof InetSocketAddress) {
        final InetSocketAddress socketAddress = (InetSocketAddress) endpoint;
        TIMEOUTS.computeIfAbsent(socketAddress.getHostString() + ":" + socketAddress.getPort(),
            it -> new ArrayList<>()).add(timeout);

        try {
          Thread.sleep(timeout);
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
        }
        throw new SocketTimeoutException("connect timed out");
      }
      throw new IllegalArgumentException("Unexpected endpoint type: " + endpoint.getClass().getName());
    }
  }
}
