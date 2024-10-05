package org.postgresql.jdbc;

import java.sql.SQLException;

@FunctionalInterface
public interface SQLFunction<T, R> {
  public R apply(T value) throws SQLException;
}
