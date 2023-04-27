/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class NumberParserTest {
  @Test
  public void testGetFastLong_normalLongs() {
    List<Long> tests = new ArrayList<>();
    for (long base : new long[]{0, 42, 65536, -65536, Long.MAX_VALUE}) {
      for (int diff = -10; diff <= 10; diff++) {
        tests.add(base + diff);
      }
    }

    for (Long test : tests) {
      assertGetLongResult(Long.toString(test), test);
    }
  }

  @Test
  public void testGetFastLong_discardsFractionalPart() {
    assertGetLongResult("234.435", 234);
    assertGetLongResult("-234234.", -234234);
  }

  @Test
  public void testGetFastLong_failOnIncorrectStrings() {
    assertGetLongFail("");
    assertGetLongFail("-234.12542.");
    assertGetLongFail(".");
    assertGetLongFail("-.");
    assertGetLongFail(Long.toString(Long.MIN_VALUE).substring(1));
  }

  private void assertGetLongResult(String s, long expected) {
    try {
      assertEquals(
          "string \"" + s + "\" parsed well to number " + expected,
          expected,
          NumberParser.getFastLong(s.getBytes(), Long.MIN_VALUE, Long.MAX_VALUE)
      );
    } catch (NumberFormatException nfe) {
      fail("failed to parse(NumberFormatException) string \"" + s + "\", expected result " + expected);
    }
  }

  private void assertGetLongFail(String s) {
    try {
      long ret = NumberParser.getFastLong(s.getBytes(), Long.MIN_VALUE, Long.MAX_VALUE);
      fail("Expected NumberFormatException on parsing \"" + s + "\", but result: " + ret);
    } catch (NumberFormatException nfe) {
      // ok
    }
  }
}
