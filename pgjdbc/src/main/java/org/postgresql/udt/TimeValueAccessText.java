/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgResultSet;

import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
//#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
import java.time.LocalTime;
//#endif
import java.util.Calendar;


/**
 * Handles {@link Oid#TIME} and {@link Oid#TIMETZ} from {@link String}.
 */
// TODO: Consider renaming "PgTime"
// TODO: Does this need to exist?
public class TimeValueAccessText extends TimeValueAccess {

  private final String value;

  public TimeValueAccessText(BaseConnection connection, int oid, String value) throws SQLException {
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
   * @see PgResultSet#getTime(int, java.util.Calendar)
   */
  @Override
  public Time getTime(Calendar cal) throws SQLException {
    if (value == null) {
      return null;
    }
    if (cal == null) {
      cal = getDefaultCalendar();
    }
    return connection.getTimestampUtils().toTime(cal, value);
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
    // If server sends us a TIME, we ensure java counterpart has date of 1970-01-01
    return new Timestamp(connection.getTimestampUtils().toTime(cal, value).getTime());
  }

  @Override
  public boolean wasNull() {
    return value == null;
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getLocalTime(int)
   */
  @Override
  public LocalTime getLocalTime() throws SQLException {
    return connection.getTimestampUtils().toLocalTime(value);
  }
  //#endif
}
