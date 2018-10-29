/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import org.postgresql.ssl.PGjdbcHostnameVerifier;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;

@RunWith(Parameterized.class)
public class CommonNameVerifierTest {

  private final String a;
  private final String b;
  private final int expected;

  public CommonNameVerifierTest(String a, String b, int expected) {
    this.a = a;
    this.b = b;
    this.expected = expected;
  }

  @Parameterized.Parameters(name = "a={0}, b={1}")
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

  @Test
  public void comparePatterns() throws Exception {
    Assert.assertEquals(a + " vs " + b,
        expected, PGjdbcHostnameVerifier.HOSTNAME_PATTERN_COMPARATOR.compare(a, b));

    Assert.assertEquals(b + " vs " + a,
        -expected, PGjdbcHostnameVerifier.HOSTNAME_PATTERN_COMPARATOR.compare(b, a));
  }
}
