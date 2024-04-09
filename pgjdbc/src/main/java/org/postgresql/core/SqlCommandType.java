/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

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
  INSERT(true),
  UPDATE(true),
  DELETE(true),
  MOVE(false),
  SELECT(true),
  WITH(true),
  CREATE(false),
  ALTER(false),
  CALL(true),
  DROP(false),
  COMMENT(false),
  PREPARE(false);

  private final boolean supportsParameters;

  SqlCommandType(boolean supportsParameters) {
    this.supportsParameters = supportsParameters;
  }

  public boolean supportsParameters() {
    return supportsParameters;
  }
}
