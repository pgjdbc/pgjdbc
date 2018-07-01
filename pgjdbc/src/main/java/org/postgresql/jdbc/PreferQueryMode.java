/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

/**
 * <p>Specifies which mode is used to execute queries to database: simple means ('Q' execute, no parse, no bind, text mode only),
 * extended means always use bind/execute messages, extendedForPrepared means extended for prepared statements only.</p>
 *
 * <p>Note: this is for debugging purposes only.</p>
 *
 * @see org.postgresql.PGProperty#PREFER_QUERY_MODE
 */
public enum PreferQueryMode {
  SIMPLE("simple"),
  EXTENDED_FOR_PREPARED("extendedForPrepared"),
  EXTENDED("extended"),
  EXTENDED_CACHE_EVERYTHING("extendedCacheEverything");

  private final String value;

  PreferQueryMode(String value) {
    this.value = value;
  }

  public static PreferQueryMode of(String mode) {
    for (PreferQueryMode preferQueryMode : values()) {
      if (preferQueryMode.value.equals(mode)) {
        return preferQueryMode;
      }
    }
    return EXTENDED;
  }

  public String value() {
    return value;
  }
}
