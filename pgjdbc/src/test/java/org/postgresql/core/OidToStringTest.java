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

public class OidToStringTest {
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {142, "XML"},
        {0, "UNSPECIFIED"},
        {-235, "<unknown:-235>"},
    });
  }

  @MethodSource("data")
  @ParameterizedTest(name = "expected={1}, value={0}")
  void run(int value, String expected) throws PSQLException {
    assertEquals(expected, Oid.toString(value));
  }
}
