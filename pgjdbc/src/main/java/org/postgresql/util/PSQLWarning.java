/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */


package org.postgresql.util;

import java.sql.SQLWarning;

public class PSQLWarning extends SQLWarning {

  private ServerErrorMessage serverError;

  public PSQLWarning(ServerErrorMessage err) {
    this.serverError = err;
  }

  public String toString() {
    return serverError.toString();
  }

  public String getSQLState() {
    return serverError.getSQLState();
  }

  public String getMessage() {
    return serverError.getMessage();
  }

  public ServerErrorMessage getServerErrorMessage() {
    return serverError;
  }
}
