/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This is the Java 11+ implementation, which looks the options up in
 * {@code jdk.net.ExtendedSocketOptions}.
 *
 * <p>This class replaces the Java 8 implementation, which finds no option, via the multi-release
 * JAR mechanism when running on Java 11+.</p>
 */
class ExtendedSocketOptionAccessorImpl implements ExtendedSocketOptionAccessor {

  private static final Logger LOGGER =
      Logger.getLogger(ExtendedSocketOptionAccessorImpl.class.getName());

  private static final String EXTENDED_SOCKET_OPTIONS_CLASS_NAME = "jdk.net.ExtendedSocketOptions";

  private static final String TCP_KEEPCOUNT = "TCP_KEEPCOUNT";
  private static final String TCP_KEEPIDLE = "TCP_KEEPIDLE";
  private static final String TCP_KEEPINTERVAL = "TCP_KEEPINTERVAL";

  private final Map<String, IntegerSocketOptionReference> integerSocketOptionByName =
      new ConcurrentHashMap<>(3);

  static final ExtendedSocketOptionAccessor
      INSTANCE = new ExtendedSocketOptionAccessorImpl();

  private ExtendedSocketOptionAccessorImpl() {
  }

  @Override
  public @Nullable ExtendedSocketIntOption findTcpKeepCountOption(Socket socket) {
    return getIntegerSocketOption(TCP_KEEPCOUNT).find(socket);
  }

  @Override
  public @Nullable ExtendedSocketIntOption findTcpKeepIdleOption(Socket socket) {
    return getIntegerSocketOption(TCP_KEEPIDLE).find(socket);
  }

  @Override
  public @Nullable ExtendedSocketIntOption findTcpKeepIntervalOption(Socket socket) {
    return getIntegerSocketOption(TCP_KEEPINTERVAL).find(socket);
  }

  private IntegerSocketOptionReference getIntegerSocketOption(String name) {
    return integerSocketOptionByName.computeIfAbsent(name,
        ExtendedSocketOptionAccessorImpl::createIntegerSocketOption);
  }

  @SuppressWarnings("unchecked")
  private static IntegerSocketOptionReference createIntegerSocketOption(String name) {
    SocketOption<Integer> socketOption;
    try {
      socketOption =
          (SocketOption<Integer>) Class.forName(EXTENDED_SOCKET_OPTIONS_CLASS_NAME,
                  false,
                  ClassLoader.getPlatformClassLoader())
              .getField(name)
              .get(null);
    } catch (ReflectiveOperationException | SecurityException | LinkageError e) {
      LOGGER.log(Level.FINE, e, () -> String.format("%s#%s seems to be unsupported by the current JDK",
          EXTENDED_SOCKET_OPTIONS_CLASS_NAME, name));
      socketOption = null;
    }
    return new IntegerSocketOptionReference(socketOption);
  }

  private static class IntegerSocketOptionReference {
    private final @Nullable SocketOption<Integer> socketOption;

    private IntegerSocketOptionReference(@Nullable SocketOption<Integer> socketOption) {
      this.socketOption = socketOption;
    }

    private @Nullable ExtendedSocketIntOption find(Socket socket) {
      if (socketOption == null || !socket.supportedOptions().contains(socketOption)) {
        return null;
      }
      return new SocketIntOption(socket, socketOption);
    }
  }

  private static class SocketIntOption implements ExtendedSocketIntOption {
    private final Socket socket;
    private final SocketOption<Integer> socketOption;

    private SocketIntOption(Socket socket, SocketOption<Integer> socketOption) {
      this.socket = socket;
      this.socketOption = socketOption;
    }

    @Override
    public void setValue(int value) throws IOException {
      socket.setOption(socketOption, value);
    }

    @Override
    public int getValue() throws IOException {
      return socket.getOption(socketOption);
    }
  }

}
