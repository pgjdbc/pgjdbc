/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import java.sql.SQLException;

public class PSQLException extends SQLException {

  private @Nullable ServerErrorMessage serverError;

  @Pure
  public PSQLException(@Nullable String msg, @Nullable PSQLState state, @Nullable Throwable cause) {
    super(msg, state == null ? null : state.getState(), cause);
  }

  @Pure
  public PSQLException(@Nullable String msg, @Nullable PSQLState state) {
    super(msg, state == null ? null : state.getState());
  }

  @Pure
  public PSQLException(ServerErrorMessage serverError) {
    this(serverError, true);
  }

  @Pure
  public PSQLException(ServerErrorMessage serverError, boolean detail) {
    super(detail ? serverError.toString() : serverError.getNonSensitiveErrorMessage(), serverError.getSQLState());
    this.serverError = serverError;
  }

  public @Pure @Nullable ServerErrorMessage getServerErrorMessage() {
    return serverError;
  }
}
