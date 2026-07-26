/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.Socket;

/**
 * Internal class, it is not a part of public API.
 *
 * <p>
 * Needed until java 11 becomes the baseline.
 */
public interface ExtendedSocketOptionAccessor {

  boolean isTcpKeepAliveCountSupported();

  void setTcpKeepAliveCount(Socket socket, int value) throws IOException;

  @Nullable
  Integer getTcpKeepAliveCount(Socket socket) throws IOException;

  boolean isTcpKeepAliveIdleSupported();

  void setTcpKeepAliveIdle(Socket socket, int value) throws IOException;

  @Nullable
  Integer getTcpKeepAliveIdle(Socket socket) throws IOException;

  boolean isTcpKeepAliveIntervalSupported();

  void setTcpKeepAliveInterval(Socket socket, int value) throws IOException;

  @Nullable
  Integer getTcpKeepAliveInterval(Socket socket) throws IOException;
}
