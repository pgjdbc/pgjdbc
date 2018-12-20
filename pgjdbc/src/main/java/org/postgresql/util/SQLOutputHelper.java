/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.sql.PreparedStatement;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLOutput;


/**
 * Helper for implementing {@link SQLOutput}.
 */
public class SQLOutputHelper {

  private SQLOutputHelper() {}

  /**
   * Writes {@link SQLData} to the given {@link PreparedStatementSQLOutput}.
   * At this time, only single attribute SQLData is supported.
   *
   * @param data the data to write
   * @param out where to write the output
   *
   * @throws SQLException when nothing written, an attempt is made to write more than
   *                      on value, or an exception occurs in the underlying
   *                      {@link PreparedStatement}.
   */
  public static void writeSQLData(SQLData data, PreparedStatementSQLOutput out) throws SQLException {
    data.writeSQL(out);
    if (!out.getWriteDone()) {
      throw new PSQLException(GT.tr(
          "No attributes written by SQLData instance of {0}",
          data.getClass().getName()), PSQLState.DATA_ERROR);
    }
  }
}
