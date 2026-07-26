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
  public boolean isTcpKeepCountSupported() {
    return false;
  }

  @Override
  public void setTcpKeepCount(Socket socket, int value) {
    throw new UnsupportedOperationException("Tuning TCP_KEEPCOUNT is only supported from JDK 11 onwards");
  }

  @Override
  public @Nullable Integer getTcpKeepCount(Socket socket) {
    return null;
  }

  @Override
  public boolean isTcpKeepIdleSupported() {
    return false;
  }

  @Override
  public void setTcpKeepIdle(Socket socket, int value) {
    throw new UnsupportedOperationException("Tuning TCP_KEEPIDLE is only supported from JDK 11 onwards");
  }

  @Override
  public @Nullable Integer getTcpKeepIdle(Socket socket) {
    return null;
  }

  @Override
  public boolean isTcpKeepIntervalSupported() {
    return false;
  }

  @Override
  public void setTcpKeepInterval(Socket socket, int value) {
    throw new UnsupportedOperationException("Tuning TCP_KEEPINTERVAL is only supported from JDK 11 onwards");
  }

  @Override
  public @Nullable Integer getTcpKeepInterval(Socket socket) {
    return null;
  }

}
