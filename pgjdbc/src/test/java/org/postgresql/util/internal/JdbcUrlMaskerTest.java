/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.postgresql.PGProperty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

class JdbcUrlMaskerTest {

  @ParameterizedTest
  @ValueSource(strings = {"password", "sslpassword"})
  void masksCredentialValue(String propertyName) {
    PGProperty property = PGProperty.forName(propertyName);
    assertNotNull(property, propertyName);
    assertTrue(property.isSensitive(), () -> propertyName + " should be sensitive");
    assertEquals(
        "jdbc:postgresql://localhost:5432/test?" + propertyName + "=***",
        JdbcUrlMasker.maskSensitiveValues(
            "jdbc:postgresql://localhost:5432/test?" + propertyName + "=secret"));
  }

  @Test
  void keepsNonSensitiveValues() {
    assertEquals(
        "jdbc:postgresql://localhost:5432/test?user=alice&password=***"
            + "&ApplicationName=pgjdbc&sslmode=disable",
        JdbcUrlMasker.maskSensitiveValues(
            "jdbc:postgresql://localhost:5432/test?user=alice&password=secret"
                + "&ApplicationName=pgjdbc&sslmode=disable"));
  }

  @Test
  void urlWithoutCredentialsIsUnchanged() {
    assertEquals("jdbc:postgresql://localhost:5432/test",
        JdbcUrlMasker.maskSensitiveValues("jdbc:postgresql://localhost:5432/test"));
  }

  static Stream<Arguments> unusualQueryStrings() {
    return Stream.of(
        // An empty value discloses nothing, and differs from a password that is not set
        argumentSet("empty value is kept",
            "jdbc:postgresql:///?password=",
            "jdbc:postgresql:///?password="),
        argumentSet("empty values are kept among others",
            "jdbc:postgresql:///?password=&sslpassword=secret",
            "jdbc:postgresql:///?password=&sslpassword=***"),
        argumentSet("parameter without a value is kept",
            "jdbc:postgresql:///?password",
            "jdbc:postgresql:///?password"),
        argumentSet("unknown parameter is kept",
            "jdbc:postgresql:///?unknownParam=x",
            "jdbc:postgresql:///?unknownParam=x"),
        argumentSet("empty parameter is kept",
            "jdbc:postgresql:///?&&ssl=true",
            "jdbc:postgresql:///?&&ssl=true"),
        // The driver ignores these names, but their values are still passwords
        argumentSet("name match ignores case",
            "jdbc:postgresql:///?Password=secret&SSLPASSWORD=passphrase",
            "jdbc:postgresql:///?Password=***&SSLPASSWORD=***"),
        // Only the first "=" separates name from value
        argumentSet("value with = is masked whole",
            "jdbc:postgresql:///?password=sec=ret",
            "jdbc:postgresql:///?password=***"),
        argumentSet("non-sensitive value is kept",
            "jdbc:postgresql:///?password=secret&options=-c%20a%3Db",
            "jdbc:postgresql:///?password=***&options=-c%20a%3Db"),
        argumentSet("not a URL", "", ""));
  }

  @ParameterizedTest
  @MethodSource("unusualQueryStrings")
  void masksUnusualQueryString(String url, String expected) {
    assertEquals(expected, JdbcUrlMasker.maskSensitiveValues(url));
  }
}
