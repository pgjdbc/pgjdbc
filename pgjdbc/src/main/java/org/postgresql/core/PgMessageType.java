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
  public static final byte QUERY = 'Q';
  public static final byte PARSE = 'P';
  public static final byte CLOSE = 'C';
  public static final byte PORTAL = 'P';
  public static final byte BIND = 'B';
  public static final byte DESCRIBE = 'D';
  public static final byte EXECUTE = 'E';
  public static final byte SYNC = 'S';
  public static final byte TERMINATE = 'X';
  public static final byte COPY_DATA = 'd';
  public static final byte COPY_DONE = 'c';
  public static final byte COPY_FAIL = 'f';
  public static final byte FUNCTION_CALL = 'F';
  public static final byte PASSWORD = 'p';
  public static final byte STATEMENT = 'S';

  // Backend message types
  public static final byte AUTHENTICATION = 'R';
  public static final byte PARAMETER_STATUS = 'S';
  public static final byte BACKEND_KEY_DATA = 'K';
  public static final byte READY_FOR_QUERY = 'Z';
  public static final byte ROW_DESCRIPTION = 'T';
  public static final byte DATA_ROW = 'D';
  public static final byte COMMAND_COMPLETE = 'C';
  public static final byte COPY_OUT_RESPONSE = 'H';
  public static final byte COPY_BOTH_RESPONSE = 'W';
  public static final byte COPY_IN_RESPONSE = 'G';
  public static final byte NEGOTIATE_PROTOCOL = 'v';
  public static final byte ERROR_RESPONSE = 'E';
  public static final byte EMPTY_QUERY_RESPONSE = 'I';
  public static final byte ASYNCHRONOUS_NOTICE = 'A';
  public static final byte NOTICE_RESPONSE = 'N';
  public static final byte PARSE_COMPLETE = '1';
  public static final byte BIND_COMPLETE = '2';
  public static final byte CLOSE_COMPLETE = '3';
  public static final byte NO_DATA = 'n';
  public static final byte PORTAL_SUSPENDED = 's';
  public static final byte PARAMETER_DESCRIPTION = 't';
  public static final byte FUNCTION_CALL_RESPONSE = 'V';
}
