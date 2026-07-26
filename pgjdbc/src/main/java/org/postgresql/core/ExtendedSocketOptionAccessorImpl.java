/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.Socket;

/**
 * Internal class, it is not a part of public API.
 */
public class ExtendedSocketOptionAccessorImpl implements ExtendedSocketOptionAccessor {

  public static final ExtendedSocketOptionAccessorImpl INSTANCE = new ExtendedSocketOptionAccessorImpl();

  private ExtendedSocketOptionAccessorImpl() {
  }

  @Override
  public boolean isTcpKeepAliveCountSupported() {
    return false;
  }

  @Override
  public void setTcpKeepAliveCount(Socket socket, int value) {
    throw new UnsupportedOperationException("Tuning TCP_KEEPCOUNT is only supported from JDK 11 onwards");
  }

  @Override
  public @Nullable Integer getTcpKeepAliveCount(Socket socket) {
    return null;
  }

  @Override
  public boolean isTcpKeepAliveIdleSupported() {
    return false;
  }

  @Override
  public void setTcpKeepAliveIdle(Socket socket, int value) {
    throw new UnsupportedOperationException("Tuning TCP_KEEPIDLE is only supported from JDK 11 onwards");
  }

  @Override
  public @Nullable Integer getTcpKeepAliveIdle(Socket socket) {
    return null;
  }

  @Override
  public boolean isTcpKeepAliveIntervalSupported() {
    return false;
  }

  @Override
  public void setTcpKeepAliveInterval(Socket socket, int value) {
    throw new UnsupportedOperationException("Tuning TCP_KEEPINTERVAL is only supported from JDK 11 onwards");
  }

  @Override
  public @Nullable Integer getTcpKeepAliveInterval(Socket socket) {
    return null;
  }

}
