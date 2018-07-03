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
public class CommandStatus {
  public final long oid;
  public final long rows;

  private static final CommandStatus EMPTY = new CommandStatus(0, 0);

  private CommandStatus(long oid, long rows) {
    this.oid = oid;
    this.rows = rows;
  }

  public static CommandStatus of(long oid, long rows) {
    if (oid == 0 && rows == 0) {
      return EMPTY;
    }
    return new CommandStatus(oid, rows);
  }

  /**
   * Parses {@code CommandComplete (B)} message
   *
   * @param status COMMAND OID ROWS message
   * @return parse result
   * @throws PSQLException in case the status cannot be parsed
   */
  public static CommandStatus of(String status) throws PSQLException {
    // This code processes the CommandComplete (B) message.
    // Status is in the format of "COMMAND OID ROWS" where both 'OID' and 'ROWS' are optional
    // and COMMAND can have spaces within it, like CREATE TABLE.
    // Scan backwards, while searching for a maximum of two number groups
    // COMMAND OID ROWS
    // COMMAND ROWS
    // Assumption: command neither starts nor ends with a digi
    if (!Parser.isDigitAt(status, status.length() - 1)) {
      return EMPTY;
    }

    long oid = 0;
    long count = 0;
    try {
      int lastSpace = status.lastIndexOf(' ');
      // Status ends with a digit => it is ROWS
      if (Parser.isDigitAt(status, lastSpace + 1)) {
        count = Parser.parseLong(status, lastSpace + 1, status.length());

        if (Parser.isDigitAt(status, lastSpace - 1)) {
          int penultimateSpace = status.lastIndexOf(' ', lastSpace - 1);
          if (Parser.isDigitAt(status, penultimateSpace + 1)) {
            oid = Parser.parseLong(status, penultimateSpace + 1, lastSpace);
          }
        }
      }
    } catch (NumberFormatException e) {
      // As we're have performed a isDigit check prior to parsing, this should only
      // occur if the oid or count are out of range.
      throw new PSQLException(
          GT.tr("Unable to parse the count in command completion tag: {0}.", status),
          PSQLState.CONNECTION_FAILURE, e);
    }
    return of(oid, count);
  }

  @Override
  public String toString() {
    return "CommandStatus{" +
        "oid=" + oid +
        ", rows=" + rows +
        '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    CommandStatus that = (CommandStatus) o;

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
