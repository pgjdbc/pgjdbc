/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.hostchooser.GlobalHostStatusTracker;
import org.postgresql.test.util.TcpKeepProbeOptionOracle;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.TestLogHandler;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Socket;
import java.net.SocketOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

class TcpKeepSettingsTest {

  @Test
  void probeOptionsASocketDoesNotSupportAreIgnoredWithAWarningOnlyTheFirstTime() throws Exception {
    Properties properties = new Properties();
    PGProperty.TCP_KEEP_ALIVE.set(properties, true);
    PGProperty.TCP_KEEP_ALIVE_COUNT.set(properties, 80);
    PGProperty.TCP_KEEP_ALIVE_IDLE.set(properties, 900);
    PGProperty.TCP_KEEP_ALIVE_INTERVAL.set(properties, 1000);
    TcpKeepSettings settings = TcpKeepSettings.read(properties);
    String reason = SocketOfThisTest.class.getName() + " does not support it on Java "
        + System.getProperty("java.version") + " on " + System.getProperty("os.name");
    List<String> expectedMessages = Arrays.asList(
        "Ignoring tcpKeepAliveCount: " + reason,
        "Ignoring tcpKeepAliveIdle: " + reason,
        "Ignoring tcpKeepAliveInterval: " + reason);

    try (Socket firstSocket = new SocketOfThisTest(); Socket secondSocket = new SocketOfThisTest()) {
      List<LogRecord> firstRecords = applyRecordingLogs(settings, firstSocket);
      List<LogRecord> secondRecords = applyRecordingLogs(settings, secondSocket);

      assertTrue(firstSocket.getKeepAlive());
      assertTrue(secondSocket.getKeepAlive());
      assertEquals(expectedMessages, formatMessages(firstRecords));
      assertEquals(Arrays.asList(Level.WARNING, Level.WARNING, Level.WARNING), extractLevels(firstRecords));
      assertEquals(expectedMessages, formatMessages(secondRecords));
      assertEquals(Arrays.asList(Level.FINE, Level.FINE, Level.FINE), extractLevels(secondRecords));
    }
  }

  @Test
  void probeOptionsASocketRefusesDespiteListingThemAreIgnoredWithAWarning() throws Exception {
    Properties properties = new Properties();
    PGProperty.TCP_KEEP_ALIVE.set(properties, true);
    PGProperty.TCP_KEEP_ALIVE_COUNT.set(properties, 70);
    PGProperty.TCP_KEEP_ALIVE_IDLE.set(properties, 800);
    PGProperty.TCP_KEEP_ALIVE_INTERVAL.set(properties, 1100);
    TcpKeepSettings settings = TcpKeepSettings.read(properties);
    String reason = SocketRefusingOptions.class.getName() + " does not support it on Java "
        + System.getProperty("java.version") + " on " + System.getProperty("os.name");

    try (Socket socket = new SocketRefusingOptions()) {
      List<LogRecord> records = applyRecordingLogs(settings, socket);

      assertTrue(socket.getKeepAlive());
      assertEquals(Arrays.asList(
          "Ignoring tcpKeepAliveCount: " + reason,
          "Ignoring tcpKeepAliveIdle: " + reason,
          "Ignoring tcpKeepAliveInterval: " + reason), formatMessages(records));
      assertEquals(Arrays.asList(Level.WARNING, Level.WARNING, Level.WARNING), extractLevels(records));
    }
  }

  @ParameterizedTest
  @EnumSource(TcpKeepProbeOptionOracle.class)
  void aProbeOptionTheOperatingSystemRejectsFailsTheConnectionWithoutBlamingTheHost(
      TcpKeepProbeOptionOracle optionOracle) throws Exception {
    try (Socket socket = new SocketRejectingOptions()) {
      assumeTrue(optionOracle.isSupportedBy(socket),
          "the JDK or the operating system does not support " + optionOracle);
    }
    Properties properties = new Properties();
    PGProperty.TCP_KEEP_ALIVE.set(properties, true);
    optionOracle.getProperty().set(properties, 60000);
    PGProperty.SOCKET_FACTORY.set(properties, SocketRejectingOptionsFactory.class.getName());
    // The value is rejected before the socket connects, so the host does not need to exist
    String host = UUID.randomUUID() + ".invalid";
    String url = "jdbc:postgresql://" + host + "/test";

    SQLException e = assertThrows(SQLException.class,
        () -> DriverManager.getConnection(url, properties));

    assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState());
    assertEquals(GT.tr("The operating system rejected {0}={1}: {2}",
        optionOracle.getProperty().getName(), "60000", "Invalid argument"), e.getMessage());
    Field hostStatusMap = GlobalHostStatusTracker.class.getDeclaredField("hostStatusMap");
    hostStatusMap.setAccessible(true);
    assertFalse(((Map<?, ?>) hostStatusMap.get(null)).containsKey(new HostSpec(host, 5432)));
  }

  @ParameterizedTest
  @CsvSource({
      "TCP_KEEP_ALIVE_COUNT, 0",
      "TCP_KEEP_ALIVE_COUNT, -30",
      "TCP_KEEP_ALIVE_IDLE, 0",
      "TCP_KEEP_ALIVE_IDLE, -30",
      "TCP_KEEP_ALIVE_INTERVAL, 0",
      "TCP_KEEP_ALIVE_INTERVAL, -30",
  })
  void aProbeOptionThatIsNotPositiveIsRejected(PGProperty probeOption, int value) {
    Properties properties = new Properties();
    PGProperty.TCP_KEEP_ALIVE.set(properties, true);
    probeOption.set(properties, value);

    PSQLException e = assertThrows(PSQLException.class, () -> TcpKeepSettings.read(properties));

    assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState());
    assertEquals(GT.tr("{0} parameter value must be a positive integer but was: {1}",
        probeOption.getName(), value), e.getMessage());
  }

  @ParameterizedTest
  @EnumSource(TcpKeepProbeOptionOracle.class)
  void aProbeOptionOfOneIsApplied(TcpKeepProbeOptionOracle optionOracle) throws Exception {
    Properties properties = new Properties();
    PGProperty.TCP_KEEP_ALIVE.set(properties, true);
    optionOracle.getProperty().set(properties, 1);
    TcpKeepSettings settings = TcpKeepSettings.read(properties);

    try (Socket socket = new Socket()) {
      assumeTrue(optionOracle.isSupportedBy(socket),
          "the JDK or the operating system does not support " + optionOracle);
      settings.applyTo(socket);

      assertEquals(1, optionOracle.getValue(socket));
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "false")
  void probeOptionsAreNotValidatedWhenKeepAliveIsDisabled(@Nullable String tcpKeepAlive)
      throws Exception {
    Properties properties = new Properties();
    PGProperty.TCP_KEEP_ALIVE.set(properties, tcpKeepAlive);
    PGProperty.TCP_KEEP_ALIVE_COUNT.set(properties, 0);
    PGProperty.TCP_KEEP_ALIVE_IDLE.set(properties, -30);
    PGProperty.TCP_KEEP_ALIVE_INTERVAL.set(properties, "ten");

    TcpKeepSettings settings = TcpKeepSettings.read(properties);

    try (Socket socket = new Socket()) {
      socket.setKeepAlive(true);
      settings.applyTo(socket);

      assertFalse(socket.getKeepAlive());
    }
  }

  @ParameterizedTest
  @EnumSource(TcpKeepProbeOptionOracle.class)
  void aProbeOptionIsNotAppliedWhenKeepAliveIsDisabled(TcpKeepProbeOptionOracle optionOracle)
      throws Exception {
    Properties properties = new Properties();
    PGProperty.TCP_KEEP_ALIVE.set(properties, false);
    optionOracle.getProperty().set(properties, 80);
    TcpKeepSettings settings = TcpKeepSettings.read(properties);

    try (Socket defaultSocket = new Socket(); Socket socket = new Socket()) {
      assumeTrue(optionOracle.isSupportedBy(socket),
          "the JDK or the operating system does not support " + optionOracle);
      settings.applyTo(socket);

      assertEquals(optionOracle.getValue(defaultSocket), optionOracle.getValue(socket));
    }
  }

  @ParameterizedTest
  @EnumSource(value = PGProperty.class,
      names = {"TCP_KEEP_ALIVE_COUNT", "TCP_KEEP_ALIVE_IDLE", "TCP_KEEP_ALIVE_INTERVAL"})
  void aProbeOptionThatIsNotANumberIsRejected(PGProperty probeOption) {
    Properties properties = new Properties();
    PGProperty.TCP_KEEP_ALIVE.set(properties, true);
    probeOption.set(properties, "ten");

    PSQLException e = assertThrows(PSQLException.class, () -> TcpKeepSettings.read(properties));

    assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState());
    assertEquals(GT.tr("{0} parameter value must be an integer but was: {1}",
        probeOption.getName(), "ten"), e.getMessage());
  }

  private static List<LogRecord> applyRecordingLogs(TcpKeepSettings settings, Socket socket)
      throws IOException {
    Logger logger = Logger.getLogger(TcpKeepSettings.class.getName());
    Level previousLevel = logger.getLevel();
    TestLogHandler log = new TestLogHandler();
    logger.setLevel(Level.FINE);
    logger.addHandler(log);
    try {
      settings.applyTo(socket);
    } finally {
      logger.removeHandler(log);
      logger.setLevel(previousLevel);
    }
    return new ArrayList<>(log.records);
  }

  private static List<String> formatMessages(List<LogRecord> records) {
    SimpleFormatter formatter = new SimpleFormatter();
    List<String> messages = new ArrayList<>();
    for (LogRecord record : records) {
      assertNull(record.getThrown());
      messages.add(formatter.formatMessage(record));
    }
    return messages;
  }

  private static List<Level> extractLevels(List<LogRecord> records) {
    List<Level> levels = new ArrayList<>();
    for (LogRecord record : records) {
      levels.add(record.getLevel());
    }
    return levels;
  }

  /**
   * A socket class of its own, so no other test has already reported its options as unsupported.
   */
  private static class SocketOfThisTest extends SocketWithoutExtendedOptions {
  }

  /**
   * A socket that, from JDK 9 on, lists the platform options as supported but refuses to set them,
   * like a custom {@code SocketImpl} on JDK 11, whose {@code supportedOptions()} is shared by every
   * socket in the JVM.
   */
  private static class SocketRefusingOptions extends Socket {

    // Overrides Socket.setOption(SocketOption, Object), which test sources cannot see since they
    // compile for Java 8
    public <T> Socket setOption(SocketOption<T> name, T value) {
      throw new UnsupportedOperationException("'" + name + "' not supported");
    }
  }
}
