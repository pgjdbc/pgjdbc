/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.util.PSQLException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;

public class OidValueOfTest {
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {25, "TEXT"},
        {0, "UNSPECIFIED"},
        {199, "JSON_ARRAY"},
        {100, "100"},
    });
  }

  @MethodSource("data")
  @ParameterizedTest(name = "expected={0}, value={1}")
  void run(int expected, String value) throws PSQLException {
    assertEquals(expected, Oid.valueOf(value));
  }
}
