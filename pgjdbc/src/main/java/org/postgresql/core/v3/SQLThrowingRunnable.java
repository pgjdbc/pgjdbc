/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import java.sql.SQLException;

@FunctionalInterface
public interface SQLThrowingRunnable {
  void run() throws SQLException;
}
