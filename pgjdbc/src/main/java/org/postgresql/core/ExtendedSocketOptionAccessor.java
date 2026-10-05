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

  boolean isTcpKeepCountSupported();

  void setTcpKeepCount(Socket socket, int value) throws IOException;

  @Nullable
  Integer getTcpKeepCount(Socket socket) throws IOException;

  boolean isTcpKeepIdleSupported();

  void setTcpKeepIdle(Socket socket, int value) throws IOException;

  @Nullable
  Integer getTcpKeepIdle(Socket socket) throws IOException;

  boolean isTcpKeepIntervalSupported();

  void setTcpKeepInterval(Socket socket, int value) throws IOException;

  @Nullable
  Integer getTcpKeepInterval(Socket socket) throws IOException;
}
