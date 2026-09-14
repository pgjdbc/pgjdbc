/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.postgresql.PGProperty;
import org.postgresql.util.GT;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;

/**
 * Resolves {@value ProtocolHardeningMode#SYSTEM_PROPERTY} to {@link ProtocolHardeningMode#FAIL}
 * unless the value names another constant, matched case-insensitively after trimming.
 *
 * <p>An unset, empty, or unrecognized value selects {@code FAIL}, so a typo in the flag leaves the
 * limits enforced, and only an unrecognized value logs a WARNING that carries the value. The mode
 * is a JVM system property and no connection property, because a connection string must not be
 * able to relax a protocol check.</p>
 *
 * <p>The tests write the system property, so each one restores it and holds the
 * {@link Resources#SYSTEM_PROPERTIES} lock.</p>
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ProtocolHardeningModeTest {
  private static final String PROPERTY = ProtocolHardeningMode.SYSTEM_PROPERTY;

  private @Nullable String savedValue;
  private final Logger logger = Logger.getLogger(ProtocolHardeningMode.class.getName());
  private final List<LogRecord> records = new ArrayList<>();
  private final Handler recorder = new Handler() {
    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
  };

  @BeforeAll
  static void initializeClassBeforeAnyTestSetsTheProperty() {
    // Class initialization reads the property too, and would log a second warning inside a test.
    assertNotEquals(null, ProtocolHardeningMode.CURRENT);
  }

  @BeforeEach
  void recordLogAndSaveProperty() {
    savedValue = System.getProperty(PROPERTY);
    logger.addHandler(recorder);
  }

  @AfterEach
  void restoreLogAndProperty() {
    logger.removeHandler(recorder);
    if (savedValue == null) {
      System.clearProperty(PROPERTY);
    } else {
      System.setProperty(PROPERTY, savedValue);
    }
  }

  @Test
  void anUnsetPropertySelectsFailWithoutAWarning() {
    System.clearProperty(PROPERTY);
    assertAll(
        () -> assertEquals(ProtocolHardeningMode.FAIL, ProtocolHardeningMode.fromSystemProperty()),
        () -> assertEquals(0, records.size(), () -> "log records: " + describe(records))
    );
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void eachConstantIsSelectedByItsLowercaseName(ProtocolHardeningMode mode) {
    System.setProperty(PROPERTY, mode.name().toLowerCase(Locale.ROOT));
    assertEquals(mode, ProtocolHardeningMode.fromSystemProperty());
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void eachConstantIsSelectedInUppercaseWithSurroundingWhitespace(ProtocolHardeningMode mode) {
    System.setProperty(PROPERTY, " \t" + mode.name() + " \n");
    assertEquals(mode, ProtocolHardeningMode.fromSystemProperty());
  }

  @Test
  void mixedCaseDisableSelectsDisable() {
    System.setProperty(PROPERTY, "DiSaBlE");
    assertEquals(ProtocolHardeningMode.DISABLE, ProtocolHardeningMode.fromSystemProperty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void anEmptyOrBlankValueSelectsFailWithoutAWarning(String value) {
    System.setProperty(PROPERTY, value);
    assertAll(
        () -> assertEquals(ProtocolHardeningMode.FAIL, ProtocolHardeningMode.fromSystemProperty()),
        () -> assertEquals(0, records.size(), () -> "log records: " + describe(records))
    );
  }

  static Stream<Arguments> unrecognizedValues() {
    return Stream.of(
        argumentSet("a near miss of disable", "disabled"),
        argumentSet("a boolean", "false"),
        argumentSet("disable with an inner space", "dis able"),
        argumentSet("a quoted token", "\"disable\"")
    );
  }

  @ParameterizedTest
  @MethodSource("unrecognizedValues")
  void anUnrecognizedValueSelectsFail(String value) {
    System.setProperty(PROPERTY, value);
    assertEquals(ProtocolHardeningMode.FAIL, ProtocolHardeningMode.fromSystemProperty());
  }

  static Stream<Arguments> warnedValues() {
    return Stream.of(
        argumentSet("a near miss with surrounding spaces", " Disabled "),
        argumentSet("a value with a placeholder and quotes", "{0}'disable'")
    );
  }

  /**
   * The warning text is compared with the formatter output for the msgid copied from
   * {@code fromSystemProperty}, so the check holds in any locale. A value that contains
   * {@code {0}} or a quote appears in the text as it is, because the record carries no
   * parameters for the handler to format a second time.
   */
  @ParameterizedTest
  @MethodSource("warnedValues")
  void anUnrecognizedValueLogsOneWarningCarryingTheValue(String value) {
    System.setProperty(PROPERTY, value);

    ProtocolHardeningMode.fromSystemProperty();

    assertEquals(1, records.size(), () -> "log records: " + describe(records));
    LogRecord record = records.get(0);
    String text = new SimpleFormatter().formatMessage(record);
    assertAll(
        () -> assertEquals(Level.WARNING, record.getLevel()),
        () -> assertEquals(
            GT.tr("System property {0} has the unrecognized value {1}; expected fail or disable. Using fail, so the protocol limits stay enforced.",
                PROPERTY, value),
            text),
        () -> assertTrue(text.contains(value),
            () -> "warning text <" + text + "> contains the raw value <" + value + ">"),
        () -> assertTrue(text.contains(PROPERTY),
            () -> "warning text <" + text + "> contains " + PROPERTY)
    );
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aRecognizedValueLogsNothing(ProtocolHardeningMode mode) {
    System.setProperty(PROPERTY, mode.name().toLowerCase(Locale.ROOT));
    ProtocolHardeningMode.fromSystemProperty();
    assertEquals(0, records.size(), () -> "log records: " + describe(records));
  }

  /**
   * {@code fromSystemProperty} reads the property on each call, so a second call sees a value set
   * after the first.
   */
  @Test
  void eachCallReadsThePropertyAfresh() {
    System.setProperty(PROPERTY, "disable");
    ProtocolHardeningMode first = ProtocolHardeningMode.fromSystemProperty();
    System.setProperty(PROPERTY, "fail");
    ProtocolHardeningMode second = ProtocolHardeningMode.fromSystemProperty();
    assertAll(
        () -> assertEquals(ProtocolHardeningMode.DISABLE, first, "first call, property disable"),
        () -> assertEquals(ProtocolHardeningMode.FAIL, second, "second call, property fail")
    );
  }

  /**
   * {@link ProtocolHardeningMode#CURRENT} takes its value from the property when the class is
   * initialized. The class is already initialized in this JVM, so the test loads a second copy in
   * a class loader that does not delegate to the application class path.
   */
  static Stream<Arguments> currentValues() {
    return Stream.of(
        argumentSet("an unset property", null, "FAIL"),
        argumentSet("disable", "disable", "DISABLE")
    );
  }

  @ParameterizedTest
  @MethodSource("currentValues")
  void currentIsReadFromThePropertyAtClassInitialization(@Nullable String value, String expected)
      throws Exception {
    if (value == null) {
      System.clearProperty(PROPERTY);
    } else {
      System.setProperty(PROPERTY, value);
    }
    URL classes = ProtocolHardeningMode.class.getProtectionDomain().getCodeSource().getLocation();
    try (URLClassLoader loader = new URLClassLoader(new URL[]{classes}, null)) {
      Class<?> isolated = Class.forName(ProtocolHardeningMode.class.getName(), true, loader);
      assertNotEquals(ProtocolHardeningMode.class, isolated, "the class must load in isolation");
      Object current = isolated.getField("CURRENT").get(null);
      assertEquals(expected, ((Enum<?>) current).name());
    }
  }

  /**
   * A connection string must not be able to relax a protocol check, so no connection property
   * carries the mode's name.
   */
  @Test
  void noConnectionPropertyExposesTheMode() {
    for (PGProperty property : PGProperty.values()) {
      String name = property.getName().toLowerCase(Locale.ROOT);
      assertAll(
          () -> assertNotEquals("protocolhardeningmode", name, property.name()),
          () -> assertNotEquals(PROPERTY.toLowerCase(Locale.ROOT), name, property.name())
      );
    }
  }

  private static String describe(List<LogRecord> records) {
    StringBuilder sb = new StringBuilder();
    for (LogRecord r : records) {
      sb.append(r.getLevel()).append(' ').append(r.getMessage())
          .append(' ').append(Arrays.toString(r.getParameters())).append("; ");
    }
    return sb.toString();
  }
}
