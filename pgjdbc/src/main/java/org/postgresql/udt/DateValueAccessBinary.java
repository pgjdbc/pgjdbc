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
import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;


/**
 * Handles {@link Oid#DATE} from {@code byte[]}.
 */
// TODO: Consider renaming "PgDate"
public class DateValueAccessBinary extends DateValueAccess {

  private final byte[] bytes;

  public DateValueAccessBinary(BaseConnection connection, byte[] bytes, int pos, int len) throws SQLException {
    super(connection);
    int to = pos + len;
    if (len != 4 || bytes.length < to) {
      throw new PSQLException(GT.tr("Unsupported binary encoding of {0}.", getPGType()),
              PSQLState.BAD_DATETIME_FORMAT);
    } else if (pos == 0 && bytes.length == len) {
      this.bytes = bytes;
    } else {
      this.bytes = Arrays.copyOfRange(bytes, pos, to);
    }
  }

  public DateValueAccessBinary(BaseConnection connection, byte[] bytes) throws SQLException {
    super(connection);
    if (bytes == null) {
      this.bytes = null;
    } else if (bytes.length != 4) {
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
    return getDate().toString();
  }

  @Override
  public byte[] getBytes() {
    return bytes;
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getDate(int, java.util.Calendar)
   */
  @Override
  public Date getDate(Calendar cal) throws SQLException {
    if (bytes == null) {
      return null;
    }
    if (cal == null) {
      cal = getDefaultCalendar();
    }
    TimeZone tz = cal.getTimeZone();
    return connection.getTimestampUtils().toDateBin(tz, bytes);
  }

  @Override
  public boolean wasNull() {
    return bytes == null;
  }
}
