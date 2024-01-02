/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.util.PGPropertyMaxResultBufferParser;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Collection;

public class PGPropertyMaxResultBufferParserTest {
  public static Collection<Object[]> data() {
    Object[][] data = new Object[][]{
      {"100", 100L},
      {"10K", 10L * 1000},
      {"25M", 25L * 1000 * 1000},
      //next two should be too big
      {"35G", (long) (0.90 * ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax())},
      {"1T", (long) (0.90 * ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax())},
      //percent test
      {"5p", (long) (0.05 * ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax())},
      {"10pct", (long) (0.10 * ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax())},
      {"15percent",
        (long) (0.15 * ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax())},
      //for testing empty property
      {"", -1},
      {null, -1}
    };
    return Arrays.asList(data);
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}: Test with valueToParse={0}, expectedResult={1}")
  void getMaxResultBufferValue(String valueToParse, long expectedResult) {
    assertDoesNotThrow(() -> {
      long result = PGPropertyMaxResultBufferParser.parseProperty(valueToParse);
      assertEquals(expectedResult, result);
    });
  }

  @Test
  void getMaxResultBufferValueException() throws PSQLException {
    assertThrows(PSQLException.class, () -> {
      long ignore = PGPropertyMaxResultBufferParser.parseProperty("abc");
    });
  }
}
