/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;

public class AbstractBasicLob {

  /**
   * Throws an exception if the pos value exceeds the max value by which the large object API can
   * index.
   *
   * @param pos Position to write at.
   * @throws SQLException if something goes wrong
   */
  protected void assertPosition(long pos) throws SQLException {
    assertPosition(pos, 0);
  }

  /**
   * Throws an exception if the pos value exceeds the max value by which the large object API can
   * index.
   *
   * @param pos Position to write at.
   * @param len number of bytes to write.
   * @throws SQLException if something goes wrong
   */
  protected void assertPosition(long pos, long len) throws SQLException {
    if (pos < 1) {
      throw new PSQLException(GT.tr("LOB positioning offsets start at 1."),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (pos + len - 1 > Integer.MAX_VALUE) {
      throw new PSQLException(GT.tr("PostgreSQL LOBs can only index to: {0}", Integer.MAX_VALUE),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
  }

}
