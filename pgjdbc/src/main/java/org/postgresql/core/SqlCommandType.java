/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
  BEGIN(false),
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

  final String lowerName;
  static final Map<Integer, List<SqlCommandType>> sqlCommandTypeLookup = new HashMap<>();

  static {
    for (SqlCommandType value : SqlCommandType.values()) {
      if (value == BLANK) {
        continue;
      }

      sqlCommandTypeLookup.computeIfAbsent(value.lowerName.length(), f -> new ArrayList<>()).add(value);
    }
  }

  public static SqlCommandType parseCommandType(final char[] query, int offset, int wordLength) {
    final List<SqlCommandType> candidateCommands = sqlCommandTypeLookup.get(wordLength);

    if (candidateCommands == null) {
      return BLANK;
    }

    for (SqlCommandType sqlCommandType : candidateCommands) {
      if (sqlCommandType.parseKeyword(query,offset)) {
        return sqlCommandType;
      }
    }

    return BLANK;
  }

  private final boolean supportsParameters;

  SqlCommandType(boolean supportsParameters) {
    this.supportsParameters = supportsParameters;
    this.lowerName = this.name().toLowerCase(Locale.ENGLISH);
  }

  public boolean supportsParameters() {
    return supportsParameters;
  }

  /**
   Parse string to check for the presence of this keyword regardless of case.
   *
   * @param query char[] of the query statement
   * @param offset position of query to start checking
   * @return boolean indicates presence of word
   */
  public boolean parseKeyword(final char[] query, int offset) {
    if (query.length < (offset + this.lowerName.length())) {
      return false;
    }

    for (int i = 0; i < this.lowerName.length(); i++) {
      if ((query[offset + i] | 32) != this.lowerName.charAt(i)) {
        return false;
      }
    }
    return true;
  }
}
