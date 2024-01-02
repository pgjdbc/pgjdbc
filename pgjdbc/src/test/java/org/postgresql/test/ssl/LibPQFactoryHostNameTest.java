/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.ssl.PGjdbcHostnameVerifier;
import org.postgresql.ssl.jdbc4.LibPQFactory;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;

public class LibPQFactoryHostNameTest {
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {"host.com", "pattern.com", false},
        {"host.com", ".pattern.com", false},
        {"host.com", "*.pattern.com", false},
        {"host.com", "*.host.com", false},
        {"a.com", "*.host.com", false},
        {".a.com", "*.host.com", false},
        {"longhostname.com", "*.com", true},
        {"longhostname.ru", "*.com", false},
        {"host.com", "host.com", true},
        {"sub.host.com", "host.com", false},
        {"sub.host.com", "sub.host.com", true},
        {"sub.host.com", "*.host.com", true},
        {"Sub.host.com", "sub.host.com", true},
        {"sub.host.com", "Sub.host.com", true},
        {"sub.host.com", "*.hoSt.com", true},
        {"*.host.com", "host.com", false},
        {"sub.sub.host.com", "*.host.com", false}, // Wildcard should cover just one level
        {"com", "*", false}, // Wildcard should have al least one dot
    });
  }

  @MethodSource("data")
  @ParameterizedTest(name = "host={0}, pattern={1}")
  void checkPattern(String hostname, String pattern, boolean expected) throws Exception {
    assertEquals(expected, LibPQFactory.verifyHostName(hostname, pattern), hostname + ", pattern: " + pattern);

    assertEquals(expected, PGjdbcHostnameVerifier.INSTANCE.verifyHostName(hostname, pattern), hostname + ", pattern: " + pattern);
  }
}
