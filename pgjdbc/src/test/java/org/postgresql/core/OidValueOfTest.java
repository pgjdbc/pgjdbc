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
public class OidValueOfTest {
  @Parameterized.Parameter(0)
  public int expected;
  @Parameterized.Parameter(1)
  public String value;

  @Parameterized.Parameters(name = "expected={0}, value={1}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {25, "TEXT"},
        {0, "UNSPECIFIED"},
        {199, "JSON_ARRAY"},
        {100, "100"},
    });
  }

  @Test
  public void run() throws PSQLException {
    Assert.assertEquals(expected, Oid.valueOf(value));
  }
}
