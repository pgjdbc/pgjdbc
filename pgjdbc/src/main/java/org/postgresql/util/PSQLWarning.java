/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */


package org.postgresql.util;

import java.sql.SQLWarning;

public class PSQLWarning extends SQLWarning {

  private ServerErrorMessage serverError;

  public PSQLWarning(ServerErrorMessage err) {
    super(err.toString(), err.getSQLState());
    this.serverError = err;
  }

  public String getMessage() {
    return serverError.getMessage();
  }

  public ServerErrorMessage getServerErrorMessage() {
    return serverError;
  }
}
