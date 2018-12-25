/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;

import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
//#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
import java.time.LocalDateTime;
//#endif
import java.util.Calendar;


// TODO: Review for conversion compatibility with PgResultSet
//       Or best - shared implementation
// TODO: Consider renaming "PgTimestamp"
public abstract class TimestampValueAccess extends BaseValueAccess {

  private final int oid;

  public TimestampValueAccess(BaseConnection connection, int oid) {
    super(connection);
    if (oid != Oid.TIMESTAMP && oid != Oid.TIMESTAMPTZ) {
      // TODO: PSQLException here?
      throw new IllegalArgumentException("oid not in (" + Oid.TIMESTAMP + ", " + Oid.TIMESTAMPTZ + ")");
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
  public abstract Date getDate(Calendar cal) throws SQLException;

  @Override
  public abstract Time getTime(Calendar cal) throws SQLException;

  @Override
  public abstract Timestamp getTimestamp(Calendar cal) throws SQLException;

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  @Override
  public abstract LocalDateTime getLocalDateTime() throws SQLException;
  //#endif
}
