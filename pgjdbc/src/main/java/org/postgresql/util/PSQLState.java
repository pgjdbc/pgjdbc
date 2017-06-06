/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */


package org.postgresql.util;

/**
 * This class is used for holding SQLState codes.
 */
public class PSQLState implements java.io.Serializable {
  private String state;

  public String getState() {
    return this.state;
  }

  public PSQLState(String state) {
    this.state = state;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PSQLState psqlState = (PSQLState) o;
    return !(state != null ? !state.equals(psqlState.state) : psqlState.state != null);
  }

  @Override
  public int hashCode() {
    return state != null ? state.hashCode() : 0;
  }

  // begin constant state codes
  public static final PSQLState UNKNOWN_STATE = new PSQLState("");

  public static final PSQLState TOO_MANY_RESULTS = new PSQLState("0100E");

  public static final PSQLState NO_DATA = new PSQLState("02000");

  public static final PSQLState INVALID_PARAMETER_TYPE = new PSQLState("07006");

  /**
   * We could establish a connection with the server for unknown reasons. Could be a network
   * problem.
   */
  public static final PSQLState CONNECTION_UNABLE_TO_CONNECT = new PSQLState("08001");

  public static final PSQLState CONNECTION_DOES_NOT_EXIST = new PSQLState("08003");

  /**
   * The server rejected our connection attempt. Usually an authentication failure, but could be a
   * configuration error like asking for a SSL connection with a server that wasn't built with SSL
   * support.
   */
  public static final PSQLState CONNECTION_REJECTED = new PSQLState("08004");

  /**
   * After a connection has been established, it went bad.
   */
  public static final PSQLState CONNECTION_FAILURE = new PSQLState("08006");
  public static final PSQLState CONNECTION_FAILURE_DURING_TRANSACTION = new PSQLState("08007");

  /**
   * The server sent us a response the driver was not prepared for and is either bizarre datastream
   * corruption, a driver bug, or a protocol violation on the server's part.
   */
  public static final PSQLState PROTOCOL_VIOLATION = new PSQLState("08P01");

  public static final PSQLState COMMUNICATION_ERROR = new PSQLState("08S01");

  public static final PSQLState NOT_IMPLEMENTED = new PSQLState("0A000");

  public static final PSQLState DATA_ERROR = new PSQLState("22000");
  public static final PSQLState NUMERIC_VALUE_OUT_OF_RANGE = new PSQLState("22003");
  public static final PSQLState BAD_DATETIME_FORMAT = new PSQLState("22007");
  public static final PSQLState DATETIME_OVERFLOW = new PSQLState("22008");
  public static final PSQLState DIVISION_BY_ZERO = new PSQLState("22012");
  public static final PSQLState MOST_SPECIFIC_TYPE_DOES_NOT_MATCH = new PSQLState("2200G");
  public static final PSQLState INVALID_PARAMETER_VALUE = new PSQLState("22023");

  public static final PSQLState INVALID_CURSOR_STATE = new PSQLState("24000");

  public static final PSQLState TRANSACTION_STATE_INVALID = new PSQLState("25000");
  public static final PSQLState ACTIVE_SQL_TRANSACTION = new PSQLState("25001");
  public static final PSQLState NO_ACTIVE_SQL_TRANSACTION = new PSQLState("25P01");
  public static final PSQLState IN_FAILED_SQL_TRANSACTION = new PSQLState("25P02");

  public static final PSQLState INVALID_SQL_STATEMENT_NAME = new PSQLState("26000");
  public static final PSQLState INVALID_AUTHORIZATION_SPECIFICATION = new PSQLState("28000");

  public static final PSQLState STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL = new PSQLState("2F003");

  public static final PSQLState INVALID_SAVEPOINT_SPECIFICATION = new PSQLState("3B000");

  public static final PSQLState SYNTAX_ERROR = new PSQLState("42601");
  public static final PSQLState UNDEFINED_COLUMN = new PSQLState("42703");
  public static final PSQLState UNDEFINED_OBJECT = new PSQLState("42704");
  public static final PSQLState WRONG_OBJECT_TYPE = new PSQLState("42809");
  public static final PSQLState NUMERIC_CONSTANT_OUT_OF_RANGE = new PSQLState("42820");
  public static final PSQLState DATA_TYPE_MISMATCH = new PSQLState("42821");
  public static final PSQLState UNDEFINED_FUNCTION = new PSQLState("42883");
  public static final PSQLState INVALID_NAME = new PSQLState("42602");
  public static final PSQLState CANNOT_COERCE = new PSQLState("42846");

  public static final PSQLState OUT_OF_MEMORY = new PSQLState("53200");
  public static final PSQLState OBJECT_NOT_IN_STATE = new PSQLState("55000");
  public static final PSQLState OBJECT_IN_USE = new PSQLState("55006");


  public static final PSQLState SYSTEM_ERROR = new PSQLState("60000");
  public static final PSQLState IO_ERROR = new PSQLState("58030");

  public static final PSQLState UNEXPECTED_ERROR = new PSQLState("99999");
}
