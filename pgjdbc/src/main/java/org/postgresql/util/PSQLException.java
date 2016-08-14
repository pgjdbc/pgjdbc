/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.util;

import java.sql.SQLException;

public class PSQLException extends SQLException {

  private ServerErrorMessage _serverError;

  public PSQLException(String msg, String state, int vendorCode, Throwable cause) {
    super(msg, state, vendorCode, cause);
  }

  public PSQLException(String msg, PSQLState state, int vendorCode) {
    this(msg, state == null ? null : state.getState(), vendorCode, null);
  }

  public PSQLException(String msg, PSQLState state) {
    this(msg, state, 0);
  }

  public PSQLException(String msg, PSQLState state, Throwable cause) {
    this(msg, state == null ? null : state.getState(), 0, cause);
  }

  public PSQLException(ServerErrorMessage serverError) {
    this(serverError.toString(), new PSQLState(serverError.getSQLState()), serverError.getErrorCode());
    _serverError = serverError;
  }

  public ServerErrorMessage getServerErrorMessage() {
    return _serverError;
  }
}
