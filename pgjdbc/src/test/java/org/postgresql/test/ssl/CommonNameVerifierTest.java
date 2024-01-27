/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.ssl.PGjdbcHostnameVerifier;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;

public class CommonNameVerifierTest {
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {"com", "host.com", -1},
        {"*.com", "host.com", -1},
        {"*.com", "*.*.com", -1},
        {"**.com", "*.com", -1},
        {"a.com", "*.host.com", -1},
        {"host.com", "subhost.host.com", -1},
        {"host.com", "host.com", 0}
    });
  }

  @MethodSource("data")
  @ParameterizedTest(name = "a={0}, b={1}")
  void comparePatterns(String a, String b, int expected) throws Exception {
    assertEquals(expected, PGjdbcHostnameVerifier.HOSTNAME_PATTERN_COMPARATOR.compare(a, b), a + " vs " + b);

    assertEquals(-expected, PGjdbcHostnameVerifier.HOSTNAME_PATTERN_COMPARATOR.compare(b, a), b + " vs " + a);
  }
}
