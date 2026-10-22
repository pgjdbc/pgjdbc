/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.util.TcpKeepProbeOptionOracle;
import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;

import javax.net.SocketFactory;

/**
 * The {@link PGStream} constructors only connect a socket, so a loopback listener stands in for
 * the server.
 */
class PGStreamKeepAliveTest {

  private ServerSocket serverSocket;

  @BeforeEach
  void listen() throws IOException {
    serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
  }

  @AfterEach
  void close() throws IOException {
    serverSocket.close();
  }

  @ParameterizedTest
  @EnumSource(TcpKeepProbeOptionOracle.class)
  void theSocketGetsTheKeepAliveProbeSetting(TcpKeepProbeOptionOracle optionOracle)
      throws Exception {
    Properties properties = new Properties();
    PGProperty.TCP_KEEP_ALIVE.set(properties, true);
    optionOracle.getProperty().set(properties, 80);

    try (PGStream pgStream = openStream(properties)) {
      Socket socket = pgStream.getSocket();
      assertTrue(socket.getKeepAlive());
      assumeTrue(optionOracle.isSupportedBy(socket),
          "the JDK or the operating system does not support " + optionOracle);
      assertEquals(80, optionOracle.getValue(socket));
    }
  }

  @ParameterizedTest
  @EnumSource(TcpKeepProbeOptionOracle.class)
  void aCopyAppliesTheKeepAliveProbeSettingToItsSocket(TcpKeepProbeOptionOracle optionOracle)
      throws Exception {
    Properties properties = new Properties();
    PGProperty.TCP_KEEP_ALIVE.set(properties, true);
    optionOracle.getProperty().set(properties, 80);

    try (PGStream pgStream = openStream(properties)) {
      assumeTrue(optionOracle.isSupportedBy(pgStream.getSocket()),
          "the JDK or the operating system does not support " + optionOracle);
      optionOracle.setValue(pgStream.getSocket(), 81);

      try (PGStream newStream = new PGStream(pgStream, 10)) {
        assertEquals(80, optionOracle.getValue(newStream.getSocket()));
      }
    }
  }

  @ParameterizedTest
  @EnumSource(TcpKeepProbeOptionOracle.class)
  void aCopyLeavesAnUnconfiguredKeepAliveProbeOptionAtItsDefault(
      TcpKeepProbeOptionOracle optionOracle) throws Exception {
    Properties properties = new Properties();
    PGProperty.TCP_KEEP_ALIVE.set(properties, true);

    try (Socket defaultSocket = new Socket(); PGStream pgStream = openStream(properties)) {
      assumeTrue(optionOracle.isSupportedBy(defaultSocket),
          "the JDK or the operating system does not support " + optionOracle);
      int defaultValue = optionOracle.getValue(defaultSocket);
      optionOracle.setValue(pgStream.getSocket(), defaultValue + 1);

      try (PGStream newStream = new PGStream(pgStream, 10)) {
        assertEquals(defaultValue, optionOracle.getValue(newStream.getSocket()));
      }
    }
  }

  @Test
  void aCopyKeepsTheKeepAliveEnabledByTheSettings() throws Exception {
    Properties properties = new Properties();
    PGProperty.TCP_KEEP_ALIVE.set(properties, true);

    try (PGStream pgStream = openStream(properties);
         PGStream newStream = new PGStream(pgStream, 10)) {
      assertTrue(newStream.getSocket().getKeepAlive());
    }
  }

  private PGStream openStream(Properties properties) throws Exception {
    HostSpec hostSpec = new HostSpec(InetAddress.getLoopbackAddress().getHostAddress(),
        serverSocket.getLocalPort());
    return new PGStream(SocketFactory.getDefault(), hostSpec, 0,
        PGProperty.MAX_SEND_BUFFER_SIZE.getInt(properties), TcpKeepSettings.read(properties));
  }
}
