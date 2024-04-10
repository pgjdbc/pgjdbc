/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Type information inspection support.
 *
 * @author Jeremy Whiting jwhiting@redhat.com
 */

public enum SqlCommandType {

  /**
   * Use BLANK for empty sql queries or when parsing the sql string is not necessary. Allow
   * parameters in order for {@link java.sql.Connection#nativeSQL} to process fragments.
   */
  BLANK(true),
  ALTER(false),
  CALL(true),
  CREATE(false),
  DELETE(true),
  INSERT(true),
  MERGE(true),
  MOVE(false),
  PREPARE(false),
  SELECT(true),
  UPDATE(true),
  WITH(false);

  static final Map<Integer, Map<String, SqlCommandType>> sqlCommandTypeLookup = new HashMap<>();

  static {
    for (SqlCommandType value : SqlCommandType.values()) {
      if (value == BLANK) {
        continue;
      }

      final String name = value.name();
      String lowerName = name.toLowerCase(Locale.ENGLISH);
      sqlCommandTypeLookup.computeIfAbsent(name.length(), f -> new HashMap<>()).put(lowerName, value);
    }
  }

  public static SqlCommandType parse(final char[] query, int offset, int wordLength) {
    final Map<String, SqlCommandType> candidateCommands = sqlCommandTypeLookup.get(wordLength);

    if (candidateCommands == null) {
      return BLANK;
    }

    entryLoop:
    for (Map.Entry<String, SqlCommandType> entry : candidateCommands.entrySet()) {
      for (int i = 0; i < wordLength; i++) {
        if ((query[offset + i] | 32) != entry.getKey().charAt(i)) {
          continue entryLoop;
        }
      }

      return entry.getValue();
    }

    return BLANK;
  }

  private final boolean supportsParameters;

  SqlCommandType(boolean supportsParameters) {
    this.supportsParameters = supportsParameters;
  }

  public boolean supportsParameters() {
    return supportsParameters;
  }
}
