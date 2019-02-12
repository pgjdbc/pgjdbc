/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgResultSet;

import java.sql.SQLException;
import java.sql.Timestamp;
//#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
import java.time.LocalDateTime;
//#endif
import java.util.Calendar;


/**
 * Handles {@link Oid#TIMESTAMP} and {@link Oid#TIMESTAMPTZ} from {@link String}.
 */
// TODO: Consider renaming "PgTimestamp"
// TODO: Does this need to exist?
public class TimestampValueAccessText extends TimestampValueAccess {

  private final String value;

  public TimestampValueAccessText(BaseConnection connection, int oid, String value) throws SQLException {
    super(connection, oid);
    this.value = value;
  }

  @Override
  public boolean isBinary() {
    return false;
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getString(int)
   */
  @Override
  public String getString() throws SQLException {
    return value;
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getTimestamp(int, java.util.Calendar)
   */
  @Override
  public Timestamp getTimestamp(Calendar cal) throws SQLException {
    if (value == null) {
      return null;
    }
    if (cal == null) {
      cal = getDefaultCalendar();
    }
    return connection.getTimestampUtils().toTimestamp(cal, value);
  }

  @Override
  public boolean wasNull() {
    return value == null;
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getLocalDateTime(int)
   */
  @Override
  public LocalDateTime getLocalDateTime() throws SQLException {
    return connection.getTimestampUtils().toLocalDateTime(value);
  }
  //#endif
}
