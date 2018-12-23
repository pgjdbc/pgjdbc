/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;
import java.sql.SQLInput;


/**
 * Partial implementation of {@link SQLInput} supporting a single read.
 */
public abstract class SingleAttributeSQLInput implements SQLInput {

  // Only one read is supported
  private boolean readDone;

  protected SingleAttributeSQLInput() {
  }

  /**
   * Marks the read as done.  If already marked, throws an exception.
   *
   * @throws SQLException if a read has already been performed
   */
  protected final void markRead() throws SQLException {
    if (readDone) {
      throw new PSQLException(GT.tr(
          "More than one read performed by SQLData.  Only single-attribute SQLData supported."),
          PSQLState.NOT_IMPLEMENTED);
    }
    readDone = true;
  }

  public final boolean getReadDone() {
    return readDone;
  }
}
