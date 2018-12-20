/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.PreparedStatement;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLOutput;


/**
 * Helper for implementing {@link SQLOutput}.
 */
class SQLOutputHelper {

  private SQLOutputHelper() {}

  /**
   * Writes {@link SQLData} to the given {@link PgPreparedStatementSQLOutput}.
   * At this time, only single attribute SQLData is supported.
   *
   * @param data the data to write
   * @param out where to write the output
   *
   * @throws SQLException when nothing written, an attempt is made to write more than
   *                      on value, or an exception occurs in the underlying
   *                      {@link PreparedStatement}.
   */
  static void writeSQLData(SQLData data, PgPreparedStatementSQLOutput out) throws SQLException {
    data.writeSQL(out);
    if (!out.getWriteDone()) {
      throw new PSQLException(GT.tr(
          "No attributes written by SQLData instance of {0}",
          data.getClass().getName()), PSQLState.DATA_ERROR);
    }
  }
}
