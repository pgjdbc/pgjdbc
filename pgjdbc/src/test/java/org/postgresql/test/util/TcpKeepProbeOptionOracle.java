/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import org.postgresql.PGProperty;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketOption;
import java.util.Set;

/**
 * Test oracle for the {@code jdk.net.ExtendedSocketOptions} keep-alive probe options: reads and
 * writes them on the {@link Socket} itself, so tests do not check the driver's accessor against
 * itself. Test sources compile for Java 8, hence the reflection.
 */
public enum TcpKeepProbeOptionOracle {
  TCP_KEEPCOUNT(PGProperty.TCP_KEEP_ALIVE_COUNT),
  TCP_KEEPIDLE(PGProperty.TCP_KEEP_ALIVE_IDLE),
  TCP_KEEPINTERVAL(PGProperty.TCP_KEEP_ALIVE_INTERVAL);

  private final PGProperty property;

  TcpKeepProbeOptionOracle(PGProperty property) {
    this.property = property;
  }

  public PGProperty getProperty() {
    return property;
  }

  public boolean isSupportedBy(Socket socket) throws ReflectiveOperationException {
    Method supportedOptions;
    try {
      supportedOptions = Socket.class.getMethod("supportedOptions");
    } catch (NoSuchMethodException e) {
      return false;
    }
    SocketOption<?> socketOption = findSocketOption();
    return socketOption != null && ((Set<?>) supportedOptions.invoke(socket)).contains(socketOption);
  }

  public int getValue(Socket socket) throws ReflectiveOperationException {
    return (Integer) Socket.class.getMethod("getOption", SocketOption.class)
        .invoke(socket, requireSocketOption());
  }

  public void setValue(Socket socket, int value) throws ReflectiveOperationException {
    Socket.class.getMethod("setOption", SocketOption.class, Object.class)
        .invoke(socket, requireSocketOption(), value);
  }

  private SocketOption<?> requireSocketOption() {
    SocketOption<?> socketOption = findSocketOption();
    if (socketOption == null) {
      throw new IllegalStateException("jdk.net.ExtendedSocketOptions." + name() + " is not available");
    }
    return socketOption;
  }

  private @Nullable SocketOption<?> findSocketOption() {
    try {
      return (SocketOption<?>) Class.forName("jdk.net.ExtendedSocketOptions", false,
              ClassLoader.getSystemClassLoader())
          .getField(name())
          .get(null);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }
}
