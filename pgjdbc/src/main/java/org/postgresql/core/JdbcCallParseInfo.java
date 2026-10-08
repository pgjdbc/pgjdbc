/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

/**
 * Contains parse flags from {@link Parser#modifyJdbcCall(String, boolean, int, EscapeSyntaxCallMode, boolean)}.
 */
public class JdbcCallParseInfo {
  private final String sql;
  private final boolean isFunction;
  private final boolean hasMultipleOutParameterForm;

  public JdbcCallParseInfo(String sql, boolean isFunction) {
    this(sql, isFunction, false);
  }

  public JdbcCallParseInfo(String sql, boolean isFunction, boolean hasMultipleOutParameterForm) {
    this.sql = sql;
    this.isFunction = isFunction;
    this.hasMultipleOutParameterForm = hasMultipleOutParameterForm;
  }

  /**
   * SQL in a native for certain backend version.
   *
   * @return SQL in a native for certain backend version
   */
  public String getSql() {
    return sql;
  }

  /**
   * Returns if given SQL is a function.
   *
   * @return {@code true} if given SQL is a function
   */
  public boolean isFunction() {
    return isFunction;
  }

  /**
   * Returns whether the statement is a {@code select} of the {@code { ? = call ... }} form, whose
   * SQL differs when the call registers two or more OUT parameters.
   *
   * @return {@code true} if the SQL depends on the {@code multipleOutParameters} argument of
   *     {@link Parser#modifyJdbcCall(String, boolean, int, EscapeSyntaxCallMode, boolean)}
   */
  public boolean hasMultipleOutParameterForm() {
    return hasMultipleOutParameterForm;
  }

}
