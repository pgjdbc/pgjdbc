/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketOption;

/**
 * A socket that, from JDK 9 on, supports the keep-alive probe options but rejects every value, like
 * an operating system that rejects a value out of its range.
 */
class SocketRejectingOptions extends Socket {

  // Overrides Socket.setOption(SocketOption, Object), which test sources cannot see since they
  // compile for Java 8
  public <T> Socket setOption(SocketOption<T> name, T value) throws IOException {
    throw new SocketException("Invalid argument");
  }
}
