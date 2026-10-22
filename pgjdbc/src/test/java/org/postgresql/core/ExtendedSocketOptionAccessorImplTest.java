/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.test.util.TcpKeepProbeOptionOracle;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

class ExtendedSocketOptionAccessorImplTest {

  private final ExtendedSocketOptionAccessor accessor = ExtendedSocketOptionAccessorImpl.INSTANCE;

  private ServerSocket serverSocket;
  private Socket socket;

  @BeforeEach
  void connect() throws IOException {
    serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    socket = new Socket(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
  }

  @AfterEach
  void close() throws IOException {
    socket.close();
    serverSocket.close();
  }

  @ParameterizedTest
  @EnumSource(TcpKeepProbeOptionOracle.class)
  void theFoundOptionSetsItsOwnSocketOption(TcpKeepProbeOptionOracle optionOracle) throws Exception {
    assumeTrue(optionOracle.isSupportedBy(socket),
        "the JDK or the operating system does not support " + optionOracle);

    requireNonNull(findOption(optionOracle, socket)).setValue(80);

    assertEquals(80, optionOracle.getValue(socket));
  }

  @ParameterizedTest
  @EnumSource(TcpKeepProbeOptionOracle.class)
  void theFoundOptionReadsItsOwnSocketOption(TcpKeepProbeOptionOracle optionOracle) throws Exception {
    assumeTrue(optionOracle.isSupportedBy(socket),
        "the JDK or the operating system does not support " + optionOracle);

    optionOracle.setValue(socket, 80);

    assertEquals(80, requireNonNull(findOption(optionOracle, socket)).getValue());
  }

  @ParameterizedTest
  @EnumSource(TcpKeepProbeOptionOracle.class)
  void findsNoOptionOnASocketThatDoesNotSupportIt(TcpKeepProbeOptionOracle optionOracle)
      throws IOException {
    try (Socket unsupportingSocket = new SocketWithoutExtendedOptions()) {
      assertNull(findOption(optionOracle, unsupportingSocket));
    }
  }

  private @Nullable ExtendedSocketIntOption findOption(TcpKeepProbeOptionOracle optionOracle,
      Socket socket) {
    switch (optionOracle) {
      case TCP_KEEPCOUNT:
        return accessor.findTcpKeepCountOption(socket);
      case TCP_KEEPIDLE:
        return accessor.findTcpKeepIdleOption(socket);
      case TCP_KEEPINTERVAL:
        return accessor.findTcpKeepIntervalOption(socket);
      default:
        throw new IllegalArgumentException("Unknown keep-alive probe option: " + optionOracle);
    }
  }
}
