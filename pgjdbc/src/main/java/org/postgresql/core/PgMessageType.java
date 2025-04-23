/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

/**
 * PostgreSQL protocol message types
 */
public class PgMessageType {
  // Frontend message types
  public static final byte BIND = 'B';
  public static final byte CLOSE_REQUEST = 'C';
  public static final byte DESCRIBE_REQUEST = 'D';
  public static final byte EXECUTE_REQUEST = 'E';
  public static final byte FUNCTION_CALL_REQ = 'F';
  public static final byte FLUSH_REQ = 'H';
  public static final byte PARSE_REQUEST = 'P';
  public static final byte QUERY_REQUEST = 'Q';
  public static final byte SYNC_REQUEST = 'S';
  public static final byte TERMINATE_REQUEST = 'X';
  public static final byte COPY_FAIL = 'f';
  public static final byte GSS_TOKEN_REQUEST = 'p';
  public static final byte PASSWORD_REQUEST = 'p';
  public static final byte SASL_RESPONSE = 'p';
  public static final byte SASL_INITIAL_RESPONSE = 'p';

  // following 2 are used for describe and close
  public static final byte PORTAL = 'P';
  public static final byte STATEMENT = 'S';

  // Backend message types
  public static final byte AUTHENTICATION_RESPONSE = 'R';
  public static final byte PARAMETER_STATUS_RESPONSE = 'S';
  public static final byte BACKEND_KEY_DATA_RESPONSE = 'K';
  public static final byte READY_FOR_QUERY_RESPONSE = 'Z';
  public static final byte ROW_DESCRIPTION_RESPONSE = 'T';
  public static final byte DATA_ROW_RESPONSE = 'D';
  public static final byte COMMAND_COMPLETE_RESPONSE = 'C';
  public static final byte COPY_OUT_RESPONSE = 'H';
  public static final byte COPY_BOTH_RESPONSE = 'W';
  public static final byte COPY_IN_RESPONSE = 'G';
  public static final byte NEGOTIATE_PROTOCOL_RESPONSE = 'v';
  public static final byte ERROR_RESPONSE = 'E';
  public static final byte EMPTY_QUERY_RESPONSE = 'I';
  public static final byte ASYNCHRONOUS_NOTICE = 'A';
  public static final byte NOTICE_RESPONSE = 'N';
  public static final byte PARSE_COMPLETE_RESPONSE = '1';
  public static final byte BIND_COMPLETE_RESPONSE = '2';
  public static final byte CLOSE_COMPLETE_RESPONSE = '3';
  public static final byte NO_DATA_RESPONSE = 'n';
  public static final byte PORTAL_SUSPENDED_RESPONSE = 's';
  public static final byte PARAMETER_DESCRIPTION_RESPONSE = 't';
  public static final byte FUNCTION_CALL_RESPONSE = 'V';

  // sent by both backend and client
  public static final byte COPY_DONE = 'c';
  public static final byte COPY_DATA = 'd';

}
