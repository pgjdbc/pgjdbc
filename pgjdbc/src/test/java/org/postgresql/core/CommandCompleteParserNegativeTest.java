/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.util.PSQLException;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;

@RunWith(Parameterized.class)
public class CommandCompleteParserNegativeTest {

  @Parameterized.Parameter(0)
  public String input;

  @Parameterized.Parameters(name = "input={0}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {"SELECT 0_0 42"},
        {"SELECT 42 0_0"},
        {"SELECT 0_0 0_0"},
    });
  }

  @Test
  public void run() throws PSQLException {
    CommandCompleteParser parser = new CommandCompleteParser();
    try {
      parser.parse(input);
      Assert.fail("CommandCompleteParser should throw NumberFormatException for " + input);
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
