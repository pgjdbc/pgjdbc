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
public class CommandCompleteParserTest {

  @Parameterized.Parameter(0)
  public String input;
  @Parameterized.Parameter(1)
  public long oid;
  @Parameterized.Parameter(2)
  public long rows;

  @Parameterized.Parameters(name = "input={0}, oid={1}, rows={2}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {"SELECT 0", 0, 0},
        {"SELECT -42", 0, 0},
        {"SELECT", 0, 0},
        {"", 0, 0},
        {"A", 0, 0},
        {"SELECT 42", 0, 42},
        {"UPDATE 43 42", 43, 42},
        {"UPDATE 43 " + Long.MAX_VALUE, 43, Long.MAX_VALUE},
        {"UPDATE " + Long.MAX_VALUE + " " + Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE},
        {"UPDATE " + (Long.MAX_VALUE / 10) + " " + (Long.MAX_VALUE / 10), (Long.MAX_VALUE / 10),
            (Long.MAX_VALUE / 10)},
        {"UPDATE " + (Long.MAX_VALUE / 100) + " " + (Long.MAX_VALUE / 100), (Long.MAX_VALUE / 100),
            (Long.MAX_VALUE / 100)},
        {"CREATE TABLE " + (Long.MAX_VALUE / 100) + " " + (Long.MAX_VALUE / 100),
            (Long.MAX_VALUE / 100), (Long.MAX_VALUE / 100)},
        {"CREATE TABLE", 0, 0},
        {"CREATE OR DROP OR DELETE TABLE 42", 0, 42},
    });
  }

  @Test
  public void run() throws PSQLException {
    CommandCompleteParser expected = new CommandCompleteParser();
    CommandCompleteParser actual = new CommandCompleteParser();
    expected.set(oid, rows);
    actual.parse(input);
    Assert.assertEquals(input, expected, actual);
  }
}
