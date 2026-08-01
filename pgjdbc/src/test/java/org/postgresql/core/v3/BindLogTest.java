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

    assertEquals(maxLen, log.length(), "capped log must be exactly max length");
    assertTrue(log.startsWith(" FE=> Bind(stmt=S_many"));
    assertTrue(log.endsWith(LogMessageUtils.TRUNCATION_SUFFIX)
            || log.contains(LogMessageUtils.TRUNCATION_SUFFIX),
        "capped log should indicate truncation");
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
    assertTrue(log.contains("bytes"), "huge value should be replaced by a size placeholder: " + log);
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
