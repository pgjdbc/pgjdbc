/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
//#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
import java.time.LocalTime;
//#endif
import java.util.Calendar;


// TODO: Consider renaming "PgTime"
public abstract class TimeValueAccess extends BaseValueAccess {

  private final int oid;

  public TimeValueAccess(BaseConnection connection, int oid) throws SQLException {
    super(connection);
    if (oid != Oid.TIME && oid != Oid.TIMETZ) {
      throw new PSQLException(
          GT.tr("Oid not in ({0}, {1}): {2} ({3})",
              Oid.toString(Oid.TIME), Oid.toString(Oid.TIMETZ), Oid.toString(oid), oid),
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

  @Override
  public abstract Time getTime(Calendar cal) throws SQLException;

  @Override
  public abstract Timestamp getTimestamp(Calendar cal) throws SQLException;

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  @Override
  public abstract LocalTime getLocalTime() throws SQLException;
  //#endif
}
