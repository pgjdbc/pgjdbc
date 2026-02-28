/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import java.sql.SQLException;

@FunctionalInterface
public interface SQLFunction<T, R> {
  R apply(T value) throws SQLException;
}
