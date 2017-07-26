/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.sql.SQLWarning;

/**
 * Wrapper class for SQLWarnings that provides an optimisation to add
 * new warnings to the tail of the SQLWarning singly linked list, avoiding Θ(n) insertion time
 * of calling #setNextWarning on the head. By encapsulating this into a single object it allows
 * users(ie PgStatement) to atomically set and clear the warning chain.
 */
public class PGSQLWarningWrapper {

  private final SQLWarning firstWarning;
  private SQLWarning lastWarning;

  public PGSQLWarningWrapper(SQLWarning warning) {
    firstWarning = warning;
    lastWarning = warning;
  }

  public void addWarning(SQLWarning sqlWarning) {
    lastWarning.setNextWarning(sqlWarning);
    lastWarning = sqlWarning;
  }

  public SQLWarning getFirstWarning() {
    return firstWarning;
  }

}
