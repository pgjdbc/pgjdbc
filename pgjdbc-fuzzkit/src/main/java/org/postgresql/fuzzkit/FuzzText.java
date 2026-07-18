/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

/**
 * Text helpers shared by the coercion fuzzers' value generators. A generated {@code String} value reaches
 * a PostgreSQL text codec, and PostgreSQL text cannot carry a NUL, so a fuzzer that draws an arbitrary
 * string must drop {@code U+0000} before the value encodes. Everything else -- multi-byte code points and
 * every other control character -- is valid text, so only NUL is removed.
 */
public final class FuzzText {

  private static final String NUL = String.valueOf((char) 0);

  private FuzzText() {
  }

  /**
   * Removes every {@code U+0000} from a fuzzer-drawn string so it encodes as PostgreSQL text; the string is
   * returned unchanged when it carries no NUL.
   *
   * @param value the drawn string
   * @return the string with every NUL removed
   */
  public static String stripNul(String value) {
    return value.indexOf(0) < 0 ? value : value.replace(NUL, "");
  }
}
