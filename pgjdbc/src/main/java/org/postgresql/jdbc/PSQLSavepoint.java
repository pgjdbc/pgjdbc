/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Utils;
import org.postgresql.exception.PgSqlState;
import org.postgresql.util.GT;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.sql.Savepoint;

public class PSQLSavepoint implements Savepoint {

  private boolean isValid;
  private final boolean isNamed;
  private int id;
  private @Nullable String name;

  public PSQLSavepoint(int id) {
    this.isValid = true;
    this.isNamed = false;
    this.id = id;
  }

  public PSQLSavepoint(String name) {
    this.isValid = true;
    this.isNamed = true;
    this.name = name;
  }

  @Override
  public int getSavepointId() throws SQLException {
    if (!isValid) {
      throw new SQLException(GT.tr("Cannot reference a savepoint after it has been released."),
          PgSqlState.INVALID_SAVEPOINT_SPECIFICATION);
    }

    if (isNamed) {
      throw new SQLException(GT.tr("Cannot retrieve the id of a named savepoint."),
          PgSqlState.WRONG_OBJECT_TYPE);
    }

    return id;
  }

  @Override
  public String getSavepointName() throws SQLException {
    if (!isValid) {
      throw new SQLException(GT.tr("Cannot reference a savepoint after it has been released."),
          PgSqlState.INVALID_SAVEPOINT_SPECIFICATION);
    }

    if (!isNamed || name == null) {
      throw new SQLException(GT.tr("Cannot retrieve the name of an unnamed savepoint."),
          PgSqlState.WRONG_OBJECT_TYPE);
    }

    return name;
  }

  public void invalidate() {
    isValid = false;
  }

  public String getPGName() throws SQLException {
    if (!isValid) {
      throw new SQLException(GT.tr("Cannot reference a savepoint after it has been released."),
          PgSqlState.INVALID_SAVEPOINT_SPECIFICATION);
    }

    if (isNamed && name != null) {
      // We need to quote and escape the name in case it
      // contains spaces/quotes/etc.
      //
      return Utils.escapeIdentifier(null, name).toString();
    }

    return "JDBC_SAVEPOINT_" + id;
  }
}
