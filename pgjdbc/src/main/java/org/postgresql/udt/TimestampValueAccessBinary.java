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
import java.sql.Timestamp;
//#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
import java.time.LocalDateTime;
//#endif
import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;


/**
 * Handles {@link Oid#TIMESTAMP} and {@link Oid#TIMESTAMPTZ} from {@code byte[]}.
 */
// TODO: Consider renaming "PgTimestamp"
public class TimestampValueAccessBinary extends TimestampValueAccess {

  private final byte[] bytes;

  public TimestampValueAccessBinary(BaseConnection connection, int oid, byte[] bytes, int pos, int len) throws SQLException {
    super(connection, oid);
    int to = pos + len;
    if (len != 8 || bytes.length < to) {
      throw new PSQLException(GT.tr("Unsupported binary encoding of {0}.", getPGType()),
              PSQLState.BAD_DATETIME_FORMAT);
    } else if (pos == 0 && bytes.length == len) {
      this.bytes = bytes;
    } else {
      this.bytes = Arrays.copyOfRange(bytes, pos, to);
    }
  }

  public TimestampValueAccessBinary(BaseConnection connection, int oid, byte[] bytes) throws SQLException {
    super(connection, oid);
    if (bytes == null) {
      this.bytes = null;
    } else if (bytes.length != 8) {
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
    return getTimestamp().toString();
  }

  @Override
  public byte[] getBytes() {
    return bytes;
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
    boolean hasTimeZone = getOid() == Oid.TIMESTAMPTZ;
    TimeZone tz = cal.getTimeZone();
    return connection.getTimestampUtils().toTimestampBin(tz, bytes, hasTimeZone);
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
   * @see PgResultSet#getLocalDateTime(int)
   */
  @Override
  public LocalDateTime getLocalDateTime() throws SQLException {
    if (bytes == null) {
      return null;
    }
    TimeZone timeZone = getDefaultCalendar().getTimeZone();
    return connection.getTimestampUtils().toLocalDateTimeBin(timeZone, bytes);
  }
  //#endif
}
