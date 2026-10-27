/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.util.EnumSet;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Builds {@code requireAuth} values from the {@link AuthMethod} enum, so the
 * tests pick up a new method without anyone editing a list. If a name built
 * here does not match what {@link AuthMethod#fromString} accepts, fromString
 * throws and the tests fail.
 */
final class RequireAuthValues {

  /** Excludes every method, so a connection that uses it must be refused. */
  static final String EVERY_METHOD_EXCLUDED =
      excluding(EnumSet.allOf(AuthMethod.class));

  private RequireAuthValues() {
  }

  /**
   * The requireAuth value that excludes each of {@code methods}, as in
   * {@code !password,!md5}.
   */
  static String excluding(EnumSet<AuthMethod> methods) {
    return methods.stream()
        .map(method ->
            "!" + method.name().toLowerCase(Locale.ROOT).replace('_', '-'))
        .collect(Collectors.joining(","));
  }
}
