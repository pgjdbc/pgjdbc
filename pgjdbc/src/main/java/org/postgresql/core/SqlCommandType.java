/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

/**
 * Type information inspection support.
 * @author Jeremy Whiting jwhiting@redhat.com
 *
 */

public enum SqlCommandType {

  /**
   * Use BLANK for empty sql queries or when parsing the sql string is not
   * necessary.
   */
  BLANK(true),
  INSERT(true),
  UPDATE(true),
  DELETE(true),
  SELECT(true),
  WITH(true),
  SHOW(true),
  MOVE(false),
  CREATE(false),
  ALTER(false),

  BEGIN(false),
  START(false),
  SET(false),
  COMMIT(false),
  END(false);

  SqlCommandType(boolean produceResult) {
    this.produceResult = produceResult;
  }

  private final boolean produceResult;

  private static final SqlCommandType[] SQL_COMMAND_TYPES = SqlCommandType.values();

  public boolean produceResult() {
    return produceResult;
  }

  /**
   * Returns the SqlCommandType for the given command status.
   * Returns BLANK if the no match is found.
   *
   * @param commandStatus the command status
   * @return the SqlCommandType for the given command status
   */
  public static SqlCommandType fromCommandStatus(String commandStatus) {
    for (SqlCommandType type : SQL_COMMAND_TYPES) {
      if (type.name().equalsIgnoreCase(commandStatus)) {
        return type;
      }
    }
    return SqlCommandType.BLANK;
  }
}
