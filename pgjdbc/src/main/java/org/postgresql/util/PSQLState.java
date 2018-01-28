/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */


package org.postgresql.util;

/**
 * This class is used for holding SQLState codes.
 */
public enum PSQLState {

  UNKNOWN_STATE(""),

  TOO_MANY_RESULTS("0100E"),

  NO_DATA("02000"),

  INVALID_PARAMETER_TYPE("07006"),

  /**
   * We could establish a connection with the server for unknown reasons. Could be a network
   * problem.
   */
  CONNECTION_UNABLE_TO_CONNECT("08001"),

  CONNECTION_DOES_NOT_EXIST("08003"),

  /**
   * The server rejected our connection attempt. Usually an authentication failure, but could be a
   * configuration error like asking for a SSL connection with a server that wasn't built with SSL
   * support.
   */
  CONNECTION_REJECTED("08004"),

  /**
   * After a connection has been established, it went bad.
   */
  CONNECTION_FAILURE("08006"),
  CONNECTION_FAILURE_DURING_TRANSACTION("08007"),

  /**
   * The server sent us a response the driver was not prepared for and is either bizarre datastream
   * corruption, a driver bug, or a protocol violation on the server's part.
   */
  PROTOCOL_VIOLATION("08P01"),

  COMMUNICATION_ERROR("08S01"),

  NOT_IMPLEMENTED("0A000"),

  DATA_ERROR("22000"),
  STRING_DATA_RIGHT_TRUNCATION("22001"),
  NUMERIC_VALUE_OUT_OF_RANGE("22003"),
  BAD_DATETIME_FORMAT("22007"),
  DATETIME_OVERFLOW("22008"),
  DIVISION_BY_ZERO("22012"),
  MOST_SPECIFIC_TYPE_DOES_NOT_MATCH("2200G"),
  INVALID_PARAMETER_VALUE("22023"),

  INVALID_CURSOR_STATE("24000"),

  TRANSACTION_STATE_INVALID("25000"),
  ACTIVE_SQL_TRANSACTION("25001"),
  NO_ACTIVE_SQL_TRANSACTION("25P01"),
  IN_FAILED_SQL_TRANSACTION("25P02"),

  INVALID_SQL_STATEMENT_NAME("26000"),
  INVALID_AUTHORIZATION_SPECIFICATION("28000"),

  STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL("2F003"),

  INVALID_SAVEPOINT_SPECIFICATION("3B000"),

  SYNTAX_ERROR("42601"),
  UNDEFINED_COLUMN("42703"),
  UNDEFINED_OBJECT("42704"),
  WRONG_OBJECT_TYPE("42809"),
  NUMERIC_CONSTANT_OUT_OF_RANGE("42820"),
  DATA_TYPE_MISMATCH("42821"),
  UNDEFINED_FUNCTION("42883"),
  INVALID_NAME("42602"),
  DATATYPE_MISMATCH("42804"),
  CANNOT_COERCE("42846"),

  OUT_OF_MEMORY("53200"),
  OBJECT_NOT_IN_STATE("55000"),
  OBJECT_IN_USE("55006"),

  QUERY_CANCELED("57014"),

  SYSTEM_ERROR("60000"),
  IO_ERROR("58030"),

  UNEXPECTED_ERROR("99999");

  private final String state;

  PSQLState(String state) {
    this.state = state;
  }

  public String getState() {
    return this.state;
  }

  public static boolean isConnectionError(String psqlState) {
    return PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState().equals(psqlState)
        || PSQLState.CONNECTION_DOES_NOT_EXIST.getState().equals(psqlState)
        || PSQLState.CONNECTION_REJECTED.getState().equals(psqlState)
        || PSQLState.CONNECTION_FAILURE.getState().equals(psqlState)
        || PSQLState.CONNECTION_FAILURE_DURING_TRANSACTION.getState().equals(psqlState);
  }

}
