/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgResultSet;

import java.sql.Date;
import java.sql.SQLException;
import java.util.Calendar;


/**
 * Handles {@link Oid#DATE} from {@link String}.
 */
// TODO: Consider renaming "PgDate"
// TODO: Does this need to exist?
public class DateValueAccessText extends DateValueAccess {

  private final String value;

  public DateValueAccessText(BaseConnection connection, String value) throws SQLException {
    super(connection);
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
   * @see PgResultSet#getDate(int, java.util.Calendar)
   */
  @Override
  public Date getDate(Calendar cal) throws SQLException {
    if (value == null) {
      return null;
    }
    if (cal == null) {
      cal = getDefaultCalendar();
    }
    return connection.getTimestampUtils().toDate(cal, value);
  }

  @Override
  public boolean wasNull() {
    return value == null;
  }
}
