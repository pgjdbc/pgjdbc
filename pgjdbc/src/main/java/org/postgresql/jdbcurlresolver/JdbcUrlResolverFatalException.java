/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbcurlresolver;

// exception for failure handling
class JdbcUrlResolverFatalException extends Exception {
  JdbcUrlResolverFatalException(String message) {
    super(message);
  }
}
