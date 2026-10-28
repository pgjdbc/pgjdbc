/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.PGProperty;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Internal class, it is not a part of public API.
 *
 * <p>TCP keep-alive options the user configured, applied to the socket the {@code socketFactory}
 * created. A probe option that is not configured keeps the value of that socket. Like libpq, the
 * probe options are only read and applied when keep-alive is enabled, an option the JDK, the
 * operating system or the socket does not support is ignored, and a value the operating system
 * rejects fails the connection. An ignored option is logged at WARNING the first time for each
 * property and socket class, and at FINE after that: the cause lasts as long as the JVM, so
 * repeating the warning on every connection would only add noise.</p>
 */
public final class TcpKeepSettings {

  private static final Logger LOGGER = Logger.getLogger(TcpKeepSettings.class.getName());

  private static final Set<String> REPORTED_UNSUPPORTED_OPTIONS = ConcurrentHashMap.newKeySet();

  /**
   * Leaves every keep-alive setting of the socket unchanged, {@code SO_KEEPALIVE} included.
   */
  public static final TcpKeepSettings NONE = new TcpKeepSettings(null, null, null, null);

  private final @Nullable Boolean alive;
  private final @Nullable Integer count;
  private final @Nullable Integer idle;
  private final @Nullable Integer interval;

  private TcpKeepSettings(@Nullable Boolean alive, @Nullable Integer count,
      @Nullable Integer idle, @Nullable Integer interval) {
    this.alive = alive;
    this.count = count;
    this.idle = idle;
    this.interval = interval;
  }

  private static @Nullable Integer readPositiveInteger(PGProperty property, Properties info)
      throws PSQLException {
    Integer value = property.getInteger(info);
    if (value == null || value > 0) {
      return value;
    }
    throw new PSQLException(GT.tr("{0} parameter value must be a positive integer but was: {1}",
        property.getName(), value), PSQLState.INVALID_PARAMETER_VALUE);
  }

  /**
   * Reads the keep-alive options from the connection properties.
   *
   * @param info the connection properties
   * @return the keep-alive options to apply to the socket
   * @throws PSQLException if {@code tcpKeepAlive} is {@code true} and a probe option is not a
   *     positive integer
   */
  public static TcpKeepSettings read(Properties info) throws
      PSQLException {
    if (!PGProperty.TCP_KEEP_ALIVE.getBoolean(info)) {
      return new TcpKeepSettings(false, null, null, null);
    }
    return new TcpKeepSettings(true,
        readPositiveInteger(PGProperty.TCP_KEEP_ALIVE_COUNT, info),
        readPositiveInteger(PGProperty.TCP_KEEP_ALIVE_IDLE, info),
        readPositiveInteger(PGProperty.TCP_KEEP_ALIVE_INTERVAL, info));
  }

  void applyTo(Socket socket) throws IOException {
    if (alive != null) {
      socket.setKeepAlive(alive);
    }
    if (!Boolean.TRUE.equals(alive)) {
      return;
    }
    ExtendedSocketOptionAccessor accessor = ExtendedSocketOptionAccessorImpl.INSTANCE;
    applySetting(PGProperty.TCP_KEEP_ALIVE_COUNT, socket, accessor::findTcpKeepCountOption, count);
    applySetting(PGProperty.TCP_KEEP_ALIVE_IDLE, socket, accessor::findTcpKeepIdleOption, idle);
    applySetting(PGProperty.TCP_KEEP_ALIVE_INTERVAL, socket, accessor::findTcpKeepIntervalOption, interval);
  }

  private static void applySetting(PGProperty property, Socket socket,
      Function<Socket, @Nullable ExtendedSocketIntOption> optionFinder, @Nullable Integer value) throws IOException {
    if (value == null) {
      return;
    }
    ExtendedSocketIntOption option = optionFinder.apply(socket);
    if (option == null) {
      logUnsupportedOption(property, socket);
      return;
    }
    try {
      option.setValue(value);
    } catch (UnsupportedOperationException e) {
      // Socket.supportedOptions() can list an option the socket then refuses: on JDK 11, before
      // JDK-8213418, it returns a set cached for every socket in the JVM
      logUnsupportedOption(property, socket);
    } catch (SocketException e) {
      throw new ValueRejectedException(GT.tr("The operating system rejected {0}={1}: {2}",
          property.getName(), String.valueOf(value), e.getMessage()), e);
    }
  }

  private static void logUnsupportedOption(PGProperty property, Socket socket) {
    String socketClassName = socket.getClass().getName();
    Level level = Level.FINE;
    if (REPORTED_UNSUPPORTED_OPTIONS.add(property.getName() + " " + socketClassName)) {
      level = Level.WARNING;
    }
    LOGGER.log(level, "Ignoring {0}: {1} does not support it on Java {2} on {3}",
        new Object[]{property.getName(), socketClassName, System.getProperty("java.version"),
            System.getProperty("os.name")});
  }

  /**
   * The operating system rejected a configured value. It would reject it on every host, so the
   * connection fails instead of trying the next host.
   */
  public static final class ValueRejectedException extends IOException {
    ValueRejectedException(String message, SocketException cause) {
      super(message, cause);
    }
  }
}
