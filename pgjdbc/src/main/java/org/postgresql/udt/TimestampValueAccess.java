/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgResultSet;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
//#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
import java.time.LocalDateTime;
//#endif
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;


// TODO: Consider renaming "PgTimestamp"
public abstract class TimestampValueAccess extends BaseValueAccess {

  private final int oid;

  public TimestampValueAccess(BaseConnection connection, int oid) throws SQLException {
    super(connection);
    if (oid != Oid.TIMESTAMP && oid != Oid.TIMESTAMPTZ) {
      throw new PSQLException(
          GT.tr("Oid not in ({0}, {1}): {2} ({3})",
              Oid.toString(Oid.TIMESTAMP), Oid.toString(Oid.TIMESTAMPTZ), Oid.toString(oid), oid),
          PSQLState.DATA_TYPE_MISMATCH);
    }
    this.oid = oid;
  }

  @Override
  protected int getOid() {
    return oid;
  }

  @Override
  public abstract boolean isBinary();

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getDate(int, java.util.Calendar)
   */
  @Override
  public Date getDate(Calendar cal) throws SQLException {
    if (cal == null) {
      cal = getDefaultCalendar();
    }
    // If backend provides just TIMESTAMP, we use "cal" timezone
    // If backend provides TIMESTAMPTZ, we ignore "cal" as we know true instant value
    Timestamp timestamp = getTimestamp(cal);
    if (timestamp == null) {
      return null;
    }
    // Here we just truncate date to 00:00 in a given time zone
    TimeZone tz = cal.getTimeZone();
    return connection.getTimestampUtils().convertToDate(timestamp.getTime(), tz);
  }


  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getTime(int, java.util.Calendar)
   */
  @Override
  public Time getTime(Calendar cal) throws SQLException {
    if (cal == null) {
      cal = getDefaultCalendar();
    }
    // If backend provides just TIMESTAMP, we use "cal" timezone
    // If backend provides TIMESTAMPTZ, we ignore "cal" as we know true instant value
    Timestamp timestamp = getTimestamp(cal);
    if (timestamp == null) {
      return null;
    }
    long timeMillis = timestamp.getTime();
    if (getOid() == Oid.TIMESTAMPTZ) {
      // time zone == UTC since BINARY "timestamp with time zone" is always sent in UTC
      // So we truncate days
      return new Time(timeMillis % TimeUnit.DAYS.toMillis(1));
    }
    // Here we just truncate date part
    TimeZone tz = cal.getTimeZone();
    return connection.getTimestampUtils().convertToTime(timeMillis, tz);
  }

  @Override
  public abstract Timestamp getTimestamp(Calendar cal) throws SQLException;

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  @Override
  public abstract LocalDateTime getLocalDateTime() throws SQLException;
  //#endif
}
