/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.Oid;
import org.postgresql.util.LogMessageUtils;

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
      // Long-ish values so the uncapped message would be well over the limit.
      params.setStringParameter(i, "value-" + i + "-" + filler, Oid.VARCHAR);
    }

    final int maxLen = 512;
    String log = BindLog.format("S_many", null, params, true, maxLen);

    assertTrue(log.length() <= maxLen, "capped log must not exceed max length: " + log.length());
    assertTrue(log.startsWith(" FE=> Bind(stmt=S_many"));
    // Cap may stop cleanly between parameters (no suffix) or mid-value (truncation suffix /
    // partial suffix). Either way late parameters must be omitted and the line stays bounded.
    assertTrue(log.contains(LogMessageUtils.TRUNCATION_SUFFIX)
            || log.contains("...")
            || log.length() < maxLen,
        "capped log should indicate truncation or stop before the cap; got: " + log);
    // Must not have expanded every parameter into the log.
    assertFalse(log.contains("$500="), "late parameters should be omitted when cap is reached");
  }

  @Test
  void hugeSingleValueIsNotMaterializedPastBudget() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(1, transferModeRegistry);
    // Large string; if fully rendered into the log without a size check this would be huge.
    String huge = repeat('H', 200_000);
    params.setStringParameter(1, huge, Oid.VARCHAR);

    final int maxLen = 256;
    String log = BindLog.format(null, null, params, true, maxLen);

    assertTrue(log.length() <= maxLen, "log length " + log.length() + " exceeds cap " + maxLen);
    assertFalse(log.contains(huge), "full parameter value must not appear in the capped log");
    // Placeholder uses a character-count estimate (not raw bytes).
    assertTrue(log.contains("chars"), "huge value should be replaced by a size placeholder: " + log);
    assertTrue(log.contains("~200000"), "placeholder should report the estimated size: " + log);
  }

  @Test
  void unknownValueTypeIsNotFullyMaterialized() throws Exception {
    SimpleParameterList params = new SimpleParameterList(1, transferModeRegistry);
    // Inject a value type estimateLogSize does not recognize. Production bind paths only store
    // String/byte[]/StreamWrapper/ByteStreamWriter/null, but the formatter must still avoid
    // calling toString on unbounded unknown objects when a length cap is set.
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
    // Fill most of the budget with one parameter, leave room only for a partial next prefix.
    SimpleParameterList params = new SimpleParameterList(3, transferModeRegistry);
    params.setStringParameter(1, repeat('a', 40), Oid.VARCHAR);
    params.setStringParameter(2, "bb", Oid.VARCHAR);
    params.setStringParameter(3, "cc", Oid.VARCHAR);

    // Cap so that after $1 (and maybe part of the trailer) there is not enough room for ",$2=<".
    final int maxLen = 80;
    String log = BindLog.format("S_dangle", null, params, true, maxLen);

    assertTrue(log.length() <= maxLen, "log length " + log.length() + " exceeds " + maxLen);
    // Must not leave a half-written parameter start such as ",$2=<" or ",$2=<...(truncated)".
    assertFalse(log.endsWith("=<")
            || log.endsWith("=<" + LogMessageUtils.TRUNCATION_SUFFIX),
        "truncated line must not leave a dangling ,$i=< prefix: " + log);
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
