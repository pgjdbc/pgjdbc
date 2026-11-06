/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.Socket;

/**
 * Needed until java 11 becomes the baseline.
 */
interface ExtendedSocketOptionAccessor {

  /**
   * Looks up {@code TCP_KEEPCOUNT} on {@code socket}, as configured by {@code tcpKeepAliveCount}.
   * It is never found on Java 8. From Java 11, whether {@code socket} accepts it depends on the
   * JDK, the operating system and the socket implementation.
   *
   * @param socket the socket to look the option up on
   * @return the option bound to {@code socket}, or {@code null} if it is not found
   */
  @Nullable
  ExtendedSocketIntOption findTcpKeepCountOption(Socket socket);

  /**
   * Looks up {@code TCP_KEEPIDLE} on {@code socket}, as configured by {@code tcpKeepAliveIdle}.
   * It is never found on Java 8. From Java 11, whether {@code socket} accepts it depends on the
   * JDK, the operating system and the socket implementation.
   *
   * @param socket the socket to look the option up on
   * @return the option bound to {@code socket}, or {@code null} if it is not found
   */
  @Nullable
  ExtendedSocketIntOption findTcpKeepIdleOption(Socket socket);

  /**
   * Looks up {@code TCP_KEEPINTERVAL} on {@code socket}, as configured by
   * {@code tcpKeepAliveInterval}. It is never found on Java 8. From Java 11, whether {@code socket}
   * accepts it depends on the JDK, the operating system and the socket implementation.
   *
   * @param socket the socket to look the option up on
   * @return the option bound to {@code socket}, or {@code null} if it is not found
   */
  @Nullable
  ExtendedSocketIntOption findTcpKeepIntervalOption(Socket socket);
}
