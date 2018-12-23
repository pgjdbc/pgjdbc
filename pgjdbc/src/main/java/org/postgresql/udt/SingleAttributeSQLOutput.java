/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;
import java.sql.SQLOutput;


/**
 * Partial implementation of {@link SQLOutput} supporting a single write.
 */
public abstract class SingleAttributeSQLOutput implements SQLOutput {

  // Only one write is supported
  private boolean writeDone;

  /**
   * Marks the write as done.  If already marked, throws an exception.
   *
   * @throws SQLException if a write has already been performed
   */
  protected final void markWrite() throws SQLException {
    if (writeDone) {
      throw new PSQLException(GT.tr(
          "More than one write performed by SQLData.  Only single-attribute SQLData supported."),
          PSQLState.NOT_IMPLEMENTED);
    }
    writeDone = true;
  }

  public final boolean getWriteDone() {
    return writeDone;
  }
}
