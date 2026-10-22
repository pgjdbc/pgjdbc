/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.Socket;

/**
 * This is the Java 8 implementation, which finds no option. JDK 8u272 added the keep-alive probe
 * options to {@code jdk.net.ExtendedSocketOptions}, but Java 8's {@code Socket} has no
 * {@code setOption}: they are set through {@code jdk.net.Sockets}, which this class does not use.
 * Java 8 support is left out by choice.
 *
 * <p>The Java 11+ implementation replaces this class via the multi-release JAR mechanism when
 * running on Java 11+.</p>
 */
class ExtendedSocketOptionAccessorImpl implements ExtendedSocketOptionAccessor {

  static final ExtendedSocketOptionAccessor INSTANCE = new ExtendedSocketOptionAccessorImpl();

  private ExtendedSocketOptionAccessorImpl() {
  }

  @Override
  public @Nullable ExtendedSocketIntOption findTcpKeepCountOption(Socket socket) {
    return null;
  }

  @Override
  public @Nullable ExtendedSocketIntOption findTcpKeepIdleOption(Socket socket) {
    return null;
  }

  @Override
  public @Nullable ExtendedSocketIntOption findTcpKeepIntervalOption(Socket socket) {
    return null;
  }

}
