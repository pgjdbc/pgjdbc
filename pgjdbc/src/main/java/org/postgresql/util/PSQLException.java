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

  public PSQLException(String msg, PSQLState state, Throwable cause) {
    super(msg, state == null ? null : state.getState(), cause);
  }

  public PSQLException(String msg, PSQLState state) {
    super(msg, state == null ? null : state.getState());
  }

  public PSQLException(ServerErrorMessage serverError) {
    super(serverError.toString(), serverError.getSQLState());
    _serverError = serverError;
  }

  public ServerErrorMessage getServerErrorMessage() {
    return _serverError;
  }
}
