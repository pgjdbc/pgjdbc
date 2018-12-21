/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;
import java.sql.SQLInput;


/**
 * Partial implementation of {@link SQLInput} supporting a single read.
 */
abstract class PgSQLInput implements SQLInput {

  // Only one read is supported
  private boolean readDone;

  PgSQLInput() {
  }

  /**
   * Marks the read as done.  If already marked, throws an exception.
   */
  final void markRead() throws SQLException {
    if (readDone) {
      throw new PSQLException(GT.tr(
          "More than one read performed by SQLData.  Only single-attribute SQLData supported."),
          PSQLState.NOT_IMPLEMENTED);
    }
    readDone = true;
  }

  final boolean getReadDone() {
    return readDone;
  }
}
