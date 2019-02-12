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

import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
//#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
import java.time.LocalTime;
//#endif
import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;


/**
 * Handles {@link Oid#TIME} and {@link Oid#TIMETZ} from {@code byte[]}.
 */
// TODO: Consider renaming "PgTime"
public class TimeValueAccessBinary extends TimeValueAccess {

  private final byte[] bytes;

  public TimeValueAccessBinary(BaseConnection connection, int oid, byte[] bytes, int pos, int len) throws SQLException {
    super(connection, oid);
    int to = pos + len;
    if ((len != 8 && len != 12) || bytes.length < to) {
      throw new PSQLException(GT.tr("Unsupported binary encoding of {0}.", getPGType()),
              PSQLState.BAD_DATETIME_FORMAT);
    } else if (pos == 0 && bytes.length == len) {
      this.bytes = bytes;
    } else {
      this.bytes = Arrays.copyOfRange(bytes, pos, to);
    }
  }

  public TimeValueAccessBinary(BaseConnection connection, int oid, byte[] bytes) throws SQLException {
    super(connection, oid);
    if (bytes == null) {
      this.bytes = null;
    } else if (bytes.length != 8 && bytes.length != 12) {
      throw new PSQLException(GT.tr("Unsupported binary encoding of {0}.", getPGType()),
              PSQLState.BAD_DATETIME_FORMAT);
    } else {
      this.bytes = bytes;
    }
  }

  @Override
  public boolean isBinary() {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getString(int)
   */
  @Override
  public String getString() throws SQLException {
    if (bytes == null) {
      return null;
    }
    return getTime().toString();
  }

  @Override
  public byte[] getBytes() {
    return bytes;
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getTime(int, java.util.Calendar)
   */
  @Override
  public Time getTime(Calendar cal) throws SQLException {
    if (bytes == null) {
      return null;
    }
    if (cal == null) {
      cal = getDefaultCalendar();
    }
    TimeZone tz = cal.getTimeZone();
    return connection.getTimestampUtils().toTimeBin(tz, bytes);
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getTimestamp(int, java.util.Calendar)
   */
  @Override
  public Timestamp getTimestamp(Calendar cal) throws SQLException {
    if (bytes == null) {
      return null;
    }
    if (cal == null) {
      cal = getDefaultCalendar();
    }
    // JDBC spec says getTimestamp of Time and Date must be supported
    return new Timestamp(getTime(cal).getTime());
  }

  @Override
  public boolean wasNull() {
    return bytes == null;
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  /**
   * {@inheritDoc}
   * <p>
   * Implemented via quick binary shortcut.
   * </p>
   *
   * @see PgResultSet#getLocalTime(int)
   */
  @Override
  public LocalTime getLocalTime() throws SQLException {
    if (bytes == null) {
      return null;
    }
    return connection.getTimestampUtils().toLocalTimeBin(bytes);
  }
  //#endif
}
