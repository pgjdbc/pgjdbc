/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.net.Socket;
import java.net.SocketOption;
import java.util.Collections;
import java.util.Set;

/**
 * A socket that, from JDK 9 on, reports no supported option, like a socket from a
 * {@code socketFactory} whose implementation does not support the keep-alive probe options.
 */
class SocketWithoutExtendedOptions extends Socket {

  // Overrides Socket.supportedOptions(), which test sources cannot see since they compile for Java 8
  public Set<SocketOption<?>> supportedOptions() {
    return Collections.emptySet();
  }
}
