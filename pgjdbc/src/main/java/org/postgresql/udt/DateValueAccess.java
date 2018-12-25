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
import java.sql.Timestamp;
import java.util.Calendar;


// TODO: Consider renaming "PgDate"
public abstract class DateValueAccess extends BaseValueAccess {

  public DateValueAccess(BaseConnection connection) {
    super(connection);
  }

  @Override
  protected int getOid() {
    return Oid.DATE;
  }

  @Override
  public abstract boolean isBinary();

  @Override
  public abstract Date getDate(Calendar cal) throws SQLException;

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getTimestamp(int, java.util.Calendar)
   */
  @Override
  public Timestamp getTimestamp(Calendar cal) throws SQLException {
    if (cal == null) {
      cal = getDefaultCalendar();
    }
    Date date = getDate(cal);
    if (date == null) {
      return null;
    }
    // JDBC spec says getTimestamp of Time and Date must be supported
    return new Timestamp(date.getTime());
  }
}
