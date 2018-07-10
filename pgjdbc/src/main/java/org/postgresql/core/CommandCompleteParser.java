/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

/**
 * Parses {@code oid} and {@code rows} from a {@code CommandComplete (B)} message (end of Execute).
 */
public final class CommandCompleteParser {
  private long oid;
  private long rows;

  public CommandCompleteParser() {
  }

  public long getOid() {
    return oid;
  }

  public long getRows() {
    return rows;
  }

  void set(long oid, long rows) {
    this.oid = oid;
    this.rows = rows;
  }

  /**
   * Parses {@code CommandComplete (B)} message.
   * Status is in the format of "COMMAND OID ROWS" where both 'OID' and 'ROWS' are optional
   * and COMMAND can have spaces within it, like CREATE TABLE.
   *
   * @param status COMMAND OID ROWS message
   * @throws PSQLException in case the status cannot be parsed
   */
  public void parse(String status) throws PSQLException {
    // Assumption: command neither starts nor ends with a digit
    if (!Parser.isDigitAt(status, status.length() - 1)) {
      set(0, 0);
      return;
    }

    // Scan backwards, while searching for a maximum of two number groups
    //   COMMAND OID ROWS
    //   COMMAND ROWS
    long oid = 0;
    long rows = 0;
    try {
      int lastSpace = status.lastIndexOf(' ');
      // Status ends with a digit => it is ROWS
      if (Parser.isDigitAt(status, lastSpace + 1)) {
        rows = Parser.parseLong(status, lastSpace + 1, status.length());

        if (Parser.isDigitAt(status, lastSpace - 1)) {
          int penultimateSpace = status.lastIndexOf(' ', lastSpace - 1);
          if (Parser.isDigitAt(status, penultimateSpace + 1)) {
            oid = Parser.parseLong(status, penultimateSpace + 1, lastSpace);
          }
        }
      }
    } catch (NumberFormatException e) {
      // This should only occur if the oid or rows are out of 0..Long.MAX_VALUE range
      throw new PSQLException(
          GT.tr("Unable to parse the count in command completion tag: {0}.", status),
          PSQLState.CONNECTION_FAILURE, e);
    }
    set(oid, rows);
  }

  @Override
  public String toString() {
    return "CommandStatus{"
        + "oid=" + oid
        + ", rows=" + rows
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    CommandCompleteParser that = (CommandCompleteParser) o;

    if (oid != that.oid) {
      return false;
    }
    return rows == that.rows;
  }

  @Override
  public int hashCode() {
    int result = (int) (oid ^ (oid >>> 32));
    result = 31 * result + (int) (rows ^ (rows >>> 32));
    return result;
  }
}
