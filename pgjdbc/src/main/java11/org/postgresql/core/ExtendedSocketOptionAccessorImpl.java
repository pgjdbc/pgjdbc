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

/**
 * Internal class, it is not a part of public API.
 */
public class ExtendedSocketOptionAccessorImpl implements ExtendedSocketOptionAccessor {

  private static final String EXTENDED_SOCKET_OPTIONS_CLASS_NAME = "jdk.net.ExtendedSocketOptions";

  private static final String TCP_KEEPCOUNT = "TCP_KEEPCOUNT";
  private static final String TCP_KEEPIDLE = "TCP_KEEPIDLE";
  private static final String TCP_KEEPINTERVAL = "TCP_KEEPINTERVAL";

  private final Map<String, SocketOptionReference<Integer>> integerSocketOptionByName =
      new ConcurrentHashMap<>(3);

  public static final ExtendedSocketOptionAccessorImpl
      INSTANCE = new ExtendedSocketOptionAccessorImpl();

  private ExtendedSocketOptionAccessorImpl() {
  }

  @Override
  public boolean isTcpKeepCountSupported() {
    return getIntegerSocketOption(TCP_KEEPCOUNT).isSupported();
  }

  @Override
  public void setTcpKeepCount(Socket socket, int value) throws IOException {
    getIntegerSocketOption(TCP_KEEPCOUNT).setValue(socket, value);
  }

  @Override
  public @Nullable Integer getTcpKeepCount(Socket socket) throws IOException {
    return getIntegerSocketOption(TCP_KEEPCOUNT).getValue(socket);
  }

  @Override
  public boolean isTcpKeepIdleSupported() {
    return getIntegerSocketOption(TCP_KEEPIDLE).isSupported();
  }

  @Override
  public void setTcpKeepIdle(Socket socket, int value) throws IOException {
    getIntegerSocketOption(TCP_KEEPIDLE).setValue(socket, value);
  }

  @Override
  public @Nullable Integer getTcpKeepIdle(Socket socket) throws IOException {
    return getIntegerSocketOption(TCP_KEEPIDLE).getValue(socket);
  }

  @Override
  public boolean isTcpKeepIntervalSupported() {
    return getIntegerSocketOption(TCP_KEEPINTERVAL).isSupported();
  }

  @Override
  public void setTcpKeepInterval(Socket socket, int value) throws IOException {
    getIntegerSocketOption(TCP_KEEPINTERVAL).setValue(socket, value);
  }

  @Override
  public @Nullable Integer getTcpKeepInterval(Socket socket) throws IOException {
    return getIntegerSocketOption(TCP_KEEPINTERVAL).getValue(socket);
  }

  private SocketOptionReference<Integer> getIntegerSocketOption(String name) {
    return integerSocketOptionByName.computeIfAbsent(name, this::createIntegerSocketOption);
  }

  @SuppressWarnings("unchecked")
  private SocketOptionReference<Integer> createIntegerSocketOption(String name) {
    try {
      SocketOption<Integer> socketOption =
          (SocketOption<Integer>) Class.forName(EXTENDED_SOCKET_OPTIONS_CLASS_NAME)
              .getField(name)
              .get(null);
      return new SocketOptionReference<>(name, socketOption);
    } catch (NoSuchFieldException | ClassNotFoundException | IllegalAccessException e) {
      return new SocketOptionReference<>(name, null);
    }
  }

  private static class SocketOptionReference<T> {
    private final String name;
    private final @Nullable SocketOption<T> socketOption;

    private SocketOptionReference(String name, @Nullable SocketOption<T> socketOption) {
      this.name = name;
      this.socketOption = socketOption;
    }

    public @Nullable T getValue(Socket socket) throws IOException {
      if (socketOption == null) {
        return null;
      }
      return socket.getOption(socketOption);
    }

    public void setValue(Socket socket, T value) throws IOException {
      if (socketOption == null) {
        throw new UnsupportedOperationException(
            String.format("%s#%s seems to be unsupported by the current JDK.",
                EXTENDED_SOCKET_OPTIONS_CLASS_NAME,
                name));
      }
      socket.setOption(socketOption, value);
    }

    public boolean isSupported() {
      return socketOption != null;
    }
  }

}
