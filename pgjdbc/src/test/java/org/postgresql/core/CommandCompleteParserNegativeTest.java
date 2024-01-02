/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.util.PSQLException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;

public class CommandCompleteParserNegativeTest {
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {"SELECT 0_0 42"},
        {"SELECT 42 0_0"},
        {"SELECT 0_0 0_0"},
    });
  }

  @MethodSource("data")
  @ParameterizedTest(name = "input={0}")
  void run(String input) throws PSQLException {
    CommandCompleteParser parser = new CommandCompleteParser();
    try {
      parser.parse(input);
      fail("CommandCompleteParser should throw NumberFormatException for " + input);
    } catch (PSQLException e) {
      Throwable cause = e.getCause();
      if (cause == null) {
        throw e;
      }
      if (!(cause instanceof NumberFormatException)) {
        throw e;
      }
      // NumerFormatException is expected
    }
  }
}
