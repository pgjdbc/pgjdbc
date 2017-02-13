/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql;

import java.sql.SQLException;

/**
 * This interface defines the public PostgreSQL extensions to java.sql.Statement. All Statements
 * constructed by the PostgreSQL driver implement PGStatement.
 */
public interface PGStatement {
  // We can't use Long.MAX_VALUE or Long.MIN_VALUE for java.sql.date
  // because this would break the 'normalization contract' of the
  // java.sql.Date API.
  // The follow values are the nearest MAX/MIN values with hour,
  // minute, second, millisecond set to 0 - this is used for
  // -infinity / infinity representation in Java
  long DATE_POSITIVE_INFINITY = 9223372036825200000L;
  long DATE_NEGATIVE_INFINITY = -9223372036832400000L;
  long DATE_POSITIVE_SMALLER_INFINITY = 185543533774800000L;
  long DATE_NEGATIVE_SMALLER_INFINITY = -185543533774800000L;


  /**
   * Return the Last inserted/updated oid.
   *
   * @return OID of last insert
   * @throws SQLException if something goes wrong
   * @since 7.3
   */
  long getLastOID() throws SQLException;

  /**
   * Turn on the use of prepared statements in the server (server side prepared statements are
   * unrelated to jdbc PreparedStatements) As of build 302, this method is equivalent to
   * <code>setPrepareThreshold(1)</code>.
   *
   * @param flag use server prepare
   * @throws SQLException if something goes wrong
   * @since 7.3
   * @deprecated As of build 302, replaced by {@link #setPrepareThreshold(int)}
   */
  @Deprecated
  void setUseServerPrepare(boolean flag) throws SQLException;

  /**
   * Check if this statement will be executed as a server-prepared statement. A return value of
   * <code>true</code> indicates that the next execution of the statement will be done as a
   * server-prepared statement, assuming the underlying protocol supports it.
   *
   * @return true if the next reuse of this statement will use a server-prepared statement
   */
  boolean isUseServerPrepare();

  /**
   * Set the reuse threshold for using server-prepared statements.
   * <p>
   * If <code>threshold</code> is a non-zero value N, the Nth and subsequent reuses of a
   * PreparedStatement will use server-side prepare.
   * <p>
   * If <code>threshold</code> is zero, server-side prepare will not be used.
   * <p>
   * The reuse threshold is only used by PreparedStatement and CallableStatement objects; it is
   * ignored for plain Statements.
   *
   * @param threshold the new threshold for this statement
   * @throws SQLException if an exception occurs while changing the threshold
   * @since build 302
   */
  void setPrepareThreshold(int threshold) throws SQLException;

  /**
   * Get the server-side prepare reuse threshold in use for this statement.
   *
   * @return the current threshold
   * @see #setPrepareThreshold(int)
   * @since build 302
   */
  int getPrepareThreshold();

  /**
   * Get the number of parameters of this statement.
   *
   * @return the number of parameters
   */
  int getParameterCount();

  /**
   * Check whether a parameter is bound.
   *
   * @param parameterIndex the index of the parameter to check
   * @return true if the parameter is bound
   */
  boolean isParameterBound(int parameterIndex);

  /**
   * Get the SQL types (as defined in {@link java.sql.Types}) of the parameters of this statement.
   * <p>
   * These types are the types defined by the user, whether implicitly or explicitly, when setting
   * parameter values.
   *
   * @return the types of the parameters
   */
  int[] getParameterTypes();

  /**
   * Get the current values of the statement parameters.
   * <p>
   * These values are the exact instances set by the user, except for primitive types which values
   * were boxed.
   * <p>
   * A value of null can be returned for a parameter that is not bound, which can be checked using
   * {@link #isParameterBound(int)}.
   *
   * @return the values of the parameters
   */
  Object[] getParameterValues();

  /**
   * Return the SQL statement as was defined: without the current template values substituted.
   * <p>
   * For plain statements, the SQL statement returned before execution is not defined.
   *
   * @return the raw SQL statement
   */
  String toString();

  /**
   * Return the SQL statement with the current template values substituted.
   * <p>
   * For plain statements, the SQL statement returned before execution is not defined.
   *
   * @return SQL statement with the current template values substituted
   */
  String toPreparedString();

}
