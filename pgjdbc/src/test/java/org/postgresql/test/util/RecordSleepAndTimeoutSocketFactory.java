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
 * This {@link SocketFactory} implementation creates {@link Socket} instances that on call to
 * {@link Socket#connect(SocketAddress, int)} would record given timeout value in {@link #TIMEOUTS}
 * map then sleep for that given amount of milliseconds and finally throw
 * {@link SocketTimeoutException} never actually trying to establish a real socket connection.
 */
public class RecordSleepAndTimeoutSocketFactory extends SocketFactory {
  public static final Map<String, List<Integer>> TIMEOUTS = new HashMap<>();

  public RecordSleepAndTimeoutSocketFactory() {
    this(null);
  }

  public RecordSleepAndTimeoutSocketFactory(@Nullable Properties ignored) {
    super();
  }

  @Override
  public Socket createSocket() {
    return new TimeoutRecordingThrowingSocket();
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

  private static class TimeoutRecordingThrowingSocket extends Socket {
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
