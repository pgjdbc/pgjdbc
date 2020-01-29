/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import static org.junit.Assert.fail;

import org.postgresql.util.PGPropertyMaxResultBufferParser;
import org.postgresql.util.PSQLException;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class PGPropertyMaxResultBufferParserTest {

  @Parameterized.Parameter(0)
  public String valueToParse;

  @Parameterized.Parameter(1)
  public long expectedResult;

  @Parameterized.Parameters(name = "{index}: Test with valueToParse={0}, expectedResult={1}")
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

  @Test
  public void testGetMaxResultBufferValue() {
    try {
      long result = PGPropertyMaxResultBufferParser.parseProperty(valueToParse);
      Assert.assertEquals(expectedResult, result);
    } catch (PSQLException e) {
      //shouldn't occur
      fail();
    }
  }

  @Test(expected = PSQLException.class)
  public void testGetMaxResultBufferValueException() throws PSQLException {
    long result = PGPropertyMaxResultBufferParser.parseProperty("abc");
    fail();
  }

}
