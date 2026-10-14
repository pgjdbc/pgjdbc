/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.Oid;
import org.postgresql.util.internal.LogMessageUtils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.SQLException;

/**
 * Unit tests for capped Bind FINEST dump formatting ({@link BindLog}).
 */
class BindLogTest {
  private TypeTransferModeRegistry transferModeRegistry;

  @BeforeEach
  void setUp() {
    transferModeRegistry = new TypeTransferModeRegistry() {
      @Override
      public boolean useBinaryForSend(int oid) {
        return false;
      }

      @Override
      public boolean useBinaryForReceive(int oid) {
        return false;
      }
    };
  }

  @Test
  void smallBindIsNotTruncated() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(2, transferModeRegistry);
    params.setIntParameter(1, 1);
    params.setIntParameter(2, 2);

    String log = BindLog.format("S_1", null, params, true, 16_384);
    assertTrue(log.startsWith(" FE=> Bind(stmt=S_1,portal=null"));
    assertTrue(log.contains("$1=<"));
    assertTrue(log.contains("$2=<"));
    assertTrue(log.endsWith(")"));
    assertFalse(log.contains(LogMessageUtils.TRUNCATION_SUFFIX));
  }

  @Test
  void manyBindsAreCapped() throws SQLException {
    final int count = 500;
    SimpleParameterList params = new SimpleParameterList(count, transferModeRegistry);
    String filler = repeat('x', 80);
    for (int i = 1; i <= count; i++) {
      params.setStringParameter(i, "value-" + i + "-" + filler, Oid.VARCHAR);
    }

    final int maxLen = 512;
    String log = BindLog.format("S_many", null, params, true, maxLen);

    assertTrue(log.length() <= maxLen, "capped log must not exceed max length: " + log.length());
    assertTrue(log.startsWith(" FE=> Bind(stmt=S_many"));
    assertTrue(log.endsWith(LogMessageUtils.TRUNCATION_SUFFIX),
        "capped log must end with truncation suffix; got: " + log);
    assertFalse(log.contains("$500="), "late parameters should be omitted when cap is reached");
  }

  @Test
  void hugeSingleValueKeepsReadablePrefix() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(1, transferModeRegistry);
    String huge = "PREFIX-" + repeat('H', 200_000);
    params.setStringParameter(1, huge, Oid.VARCHAR);

    final int maxLen = 256;
    String log = BindLog.format(null, null, params, true, maxLen);

    assertTrue(log.length() <= maxLen, "log length " + log.length() + " exceeds cap " + maxLen);
    assertFalse(log.contains(huge), "full parameter value must not appear in the capped log");
    assertTrue(log.contains("PREFIX-"), "truncated string should keep a readable prefix: " + log);
    assertTrue(log.contains("..."), "truncated string should end with ellipsis: " + log);
  }

  @Test
  void oversizedByteaUsesBytesPlaceholderAndKeepsLaterParam() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(2, transferModeRegistry);
    params.setBytea(1, new byte[10_000], 0, 10_000);
    params.setIntParameter(2, 42);

    final int maxLen = 16_384;
    String log = BindLog.format("S_bytea", null, params, true, maxLen);

    assertTrue(log.contains("<10000 bytes>"), "oversized bytea should use bytes placeholder: " + log);
    assertTrue(log.contains("$2=<"), "parameter after oversized bytea must still be logged: " + log);
    assertTrue(log.contains("42") || log.contains("int4"), "second parameter value should appear: " + log);
    assertFalse(log.contains("chars"), "bytea placeholder must not say chars: " + log);
  }

  @Test
  void unknownValueTypeIsNotFullyMaterialized() throws Exception {
    SimpleParameterList params = new SimpleParameterList(1, transferModeRegistry);
    final String huge = repeat('U', 500_000);
    Object unknown = new Object() {
      @Override
      public String toString() {
        return huge;
      }
    };
    Field paramValues = SimpleParameterList.class.getDeclaredField("paramValues");
    paramValues.setAccessible(true);
    Object[] values = (Object[]) paramValues.get(params);
    values[0] = unknown;

    final int maxLen = 256;
    String log = BindLog.format(null, null, params, true, maxLen);

    assertTrue(log.length() <= maxLen, "log length " + log.length() + " exceeds cap " + maxLen);
    assertFalse(log.contains(huge), "unknown-type toString must not be fully rendered into the log");
    assertTrue(log.contains("unestimated"), "unknown size should use an unestimated placeholder: " + log);
  }

  @Test
  void nullParameterStillRenders() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(1, transferModeRegistry);
    params.setNull(1, Oid.VARCHAR);

    String log = BindLog.format("S_null", null, params, true, 16_384);
    assertTrue(log.contains("$1=<(NULL)>"), "SQL null should still render: " + log);
    assertFalse(log.contains("unestimated"), "null is sized, not unknown");
  }

  @Test
  void budgetExhaustionDoesNotLeaveDanglingParameterPrefix() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(3, transferModeRegistry);
    params.setStringParameter(1, repeat('a', 40), Oid.VARCHAR);
    params.setStringParameter(2, "bb", Oid.VARCHAR);
    params.setStringParameter(3, "cc", Oid.VARCHAR);

    final int maxLen = 80;
    String log = BindLog.format("S_dangle", null, params, true, maxLen);

    assertTrue(log.length() <= maxLen, "log length " + log.length() + " exceeds " + maxLen);
    assertFalse(log.endsWith("=<")
            || log.endsWith("=<" + LogMessageUtils.TRUNCATION_SUFFIX),
        "truncated line must not leave a dangling ,$i=< prefix: " + log);
    assertTrue(log.endsWith(LogMessageUtils.TRUNCATION_SUFFIX)
            || log.contains(LogMessageUtils.TRUNCATION_SUFFIX),
        "omitted parameters must be marked truncated: " + log);
  }

  @Test
  void omittedParamsAlwaysMarkedTruncated() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(30, transferModeRegistry);
    for (int i = 1; i <= 30; i++) {
      params.setIntParameter(i, i);
    }
    final int maxLen = 59;
    String log = BindLog.format(null, null, params, true, maxLen);
    assertTrue(log.length() <= maxLen, "length " + log.length());
    assertTrue(log.endsWith(LogMessageUtils.TRUNCATION_SUFFIX)
            || log.contains(LogMessageUtils.TRUNCATION_SUFFIX),
        "must mark truncation when params omitted: " + log);
  }

  private static String repeat(char c, int count) {
    char[] chars = new char[count];
    java.util.Arrays.fill(chars, c);
    return new String(chars);
  }

  @Test
  void unlimitedKeepsFullDump() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(3, transferModeRegistry);
    params.setIntParameter(1, 10);
    params.setIntParameter(2, 20);
    params.setIntParameter(3, 30);

    String log = BindLog.format("S_full", null, params, true, 0);
    assertTrue(log.contains("$1=<"));
    assertTrue(log.contains("$2=<"));
    assertTrue(log.contains("$3=<"));
    assertTrue(log.endsWith(")"));
    assertFalse(log.contains(LogMessageUtils.TRUNCATION_SUFFIX));
  }
}
