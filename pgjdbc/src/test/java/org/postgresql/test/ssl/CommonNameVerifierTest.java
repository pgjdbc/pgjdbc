/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import org.postgresql.ssl.PGjdbcHostnameVerifier;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;

public class CommonNameVerifierTest {

  private String a;
  private String b;
  private int expected;

  public void initCommonNameVerifierTest(String a, String b, int expected) {
    this.a = a;
    this.b = b;
    this.expected = expected;
  }

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
  public void comparePatterns(String a, String b, int expected) throws Exception {
    initCommonNameVerifierTest(a, b, expected);
    Assertions.assertEquals(expected, PGjdbcHostnameVerifier.HOSTNAME_PATTERN_COMPARATOR.compare(a, b), a + " vs " + b);

    Assertions.assertEquals(-expected, PGjdbcHostnameVerifier.HOSTNAME_PATTERN_COMPARATOR.compare(b, a), b + " vs " + a);
  }
}
