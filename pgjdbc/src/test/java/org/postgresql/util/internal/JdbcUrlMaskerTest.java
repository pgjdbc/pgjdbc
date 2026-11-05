/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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

  static Stream<Arguments> userSuppliedUrls() {
    return Stream.of(
        arguments("jdbc:postgresql://alice:secret@db/app", "jdbc:postgresql://***@db/app"),
        arguments("jdbc:postgresql://alice:secret@db:5432/app", "jdbc:postgresql://***@db:5432/app"),
        arguments("jdbc:postgresql://alice:secret@db", "jdbc:postgresql://***@db"),
        arguments("jdbc:postgresql://alice@db/app", "jdbc:postgresql://***@db/app"),
        // The last "@" ends the user info, as a password may contain "@"
        arguments("jdbc:postgresql://alice:sec@ret@db/app", "jdbc:postgresql://***@db/app"),
        arguments("jdbc:postgresql://alice:secret@h1,h2:5433/app",
            "jdbc:postgresql://***@h1,h2:5433/app"),
        arguments("jdbc:postgresql://alice:secret@db/app?password=secret&ssl=true",
            "jdbc:postgresql://***@db/app?password=***&ssl=true"),
        // A raw "?" in the password does not end the user info
        arguments("jdbc:postgresql://alice:sec?ret@db/app", "jdbc:postgresql://***@db/app"),
        arguments("jdbc:postgresql://alice:sec?ret@db/app?password=secret&ssl=true",
            "jdbc:postgresql://***@db/app?password=***&ssl=true"),
        // A raw "/" in the password ends the user info as libpq reads it, but the "@" after it may
        // still end a password, so masking runs up to that "@", also when a "?" moved it into a
        // parameter name
        arguments("jdbc:postgresql://alice:sec/ret@db/app", "jdbc:postgresql://***@db/app"),
        arguments("jdbc:postgresql://alice:sec/ret@db", "jdbc:postgresql://***@db"),
        arguments("jdbc:postgresql://alice:pa/x?y@db/app", "jdbc:postgresql://***@db/app"),
        arguments("jdbc:postgresql://alice:1234/x?y@db/app", "jdbc:postgresql://***@db/app"),
        // A password with "/", "?" and "=" puts the "@" into the value of an unknown parameter, so
        // masking runs up to it, and a password after it is masked too
        arguments("jdbc:postgresql://alice:pa/x?a=b@db/app", "jdbc:postgresql://***@db/app"),
        arguments("jdbc:postgresql://alice:pa/x?a=b@db/app?password=secret",
            "jdbc:postgresql://***@db/app?password=***"),
        // An "@" in the value of a known parameter, such as Azure's user@server, is kept
        arguments("jdbc:postgresql://db:99999/app?user=alice@example.com&password=secret",
            "jdbc:postgresql://db:99999/app?user=alice@example.com&password=***"),
        arguments("jdbc:postgresql://db/a/b?ApplicationName=me@laptop",
            "jdbc:postgresql://db/a/b?ApplicationName=me@laptop"),
        // A name that parseURL translates to a known property counts as known
        arguments("jdbc:postgresql://db/a/b?host=a@b", "jdbc:postgresql://db/a/b?host=a@b"),
        // The known limit: a password that ends with a known parameter name and value is kept
        arguments("jdbc:postgresql://alice:pa/x?user=b@db/app",
            "jdbc:postgresql://alice:pa/x?user=b@db/app"),
        // User info is cut at its own "@", so the host and a known parameter after it are kept
        arguments("jdbc:postgresql://alice:secret@db/app?user=alice@example.com",
            "jdbc:postgresql://***@db/app?user=alice@example.com"),
        arguments("jdbc:postgresql://db/a/b?password=secret", "jdbc:postgresql://db/a/b?password=***"),
        // As in libpq, an "@" before the first "/" ends the user info even after a "?", when the text
        // after the "?" looks like a known parameter, or holds an "@" that masking the password
        // value would remove
        arguments("jdbc:postgresql://alice:sec?user=ret@db/app", "jdbc:postgresql://***@db/app"),
        arguments("jdbc:postgresql://alice:sec?password=x@db/app", "jdbc:postgresql://***@db/app"),
        // Accepted as libpq accepts it: host "alice", port "1234", and database "ret@db"
        arguments("jdbc:postgresql://alice:1234/ret@db", "jdbc:postgresql://***@db"),
        // A raw "@" in the database name cannot be told apart from user info
        arguments("jdbc:postgresql://db/my@app?password=secret",
            "jdbc:postgresql://***@app?password=***"),
        // Without "//", the text is a database name, but an "@" in it may still end a password
        arguments("jdbc:postgresql:alice:secret@db/app", "jdbc:postgresql:***@db/app"),
        arguments("jdbc:postgresql:alice:sec?ret@db/app", "jdbc:postgresql:***@db/app"));
  }

  static Stream<Arguments> userInfoEnds() {
    return Stream.of(
        arguments("jdbc:postgresql://alice:secret@db/app", 30),
        // The last "@" before the first "/" is taken, as the password may hold "@"
        arguments("jdbc:postgresql://alice:sec@ret@db/app", 31),
        // A "?" does not end the search, as in libpq
        arguments("jdbc:postgresql://alice:sec?user=ret@db/app", 36),
        // A "/" ends the search, so an "@" after it is a part of the database name or the query
        arguments("jdbc:postgresql://alice:pa/x?y@db/app", -1),
        arguments("jdbc:postgresql://db/my@app", -1),
        arguments("jdbc:postgresql://db/app?user=alice@example.com", -1),
        // Only the form with "//" has user info
        arguments("jdbc:postgresql:alice:secret@db/app", -1));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("userInfoEnds")
  void findsUserInfoEnd(String url, int expected) {
    assertEquals(expected, JdbcUrlMasker.findUserInfoEnd(url));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("userSuppliedUrls")
  void masksUserSuppliedUrl(String url, String expected) {
    assertEquals(expected, JdbcUrlMasker.maskUserSuppliedUrl(url));
  }

  /** Only values are masked, so an "@" in a parameter value is kept, such as Azure's user@server. */
  @ParameterizedTest
  @ValueSource(strings = {
      "jdbc:postgresql://db/app?user=alice@example.com&ssl=true",
      "jdbc:postgresql:app?user=alice@example.com",
  })
  void keepsAtSignInParameterValue(String url) {
    assertEquals(url, JdbcUrlMasker.maskSensitiveValues(url));
  }
}
