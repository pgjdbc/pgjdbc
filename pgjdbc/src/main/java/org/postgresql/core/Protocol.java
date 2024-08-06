/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

public class Protocol {
  // responses
  public static final int ASYNCHRONOUS_NOTIFY = 'A';
  public static final int AUTHENTICATION_RESPONSE = 'R';
  public static final int BACKEND_KEY_DATA = 'K';
  public static final int BIND_COMPLETE = '2';
  public static final int CLOSE_COMPLETE = '3';
  public static final int COMMAND_STATUS = 'C';
  public static final int COPY_BOTH_RESPONSE = 'W';
  public static final int COPY_DATA_RESPONSE = 'd';
  public static final int COPY_DONE = 'c';
  public static final int COPY_IN_RESPONSE = 'G';
  public static final int COPY_OUT_RESPONSE = 'H';
  public static final int DATA_TRANSFER = 'D';
  public static final int FUNCTION_CALL_RESPONSE = 'V';
  public static final int EMPTY_QUERY_RESPONSE = 'I';
  public static final int ERROR_RESPONSE = 'E';
  public static final int NO_DATA = 'n';
  public static final int NOTICE_RESPONSE = 'N';
  public static final int PARSE_COMPLETE = '1';
  public static final int PARAMETER_DESCRIPTION = 't';
  public static final int PARAMETER_STATUS = 'S';
  public static final int PORTAL_SUSPENDED = 's';
  public static final int READY_FOR_QUERY = 'Z';
  public static final int ROW_DESCRIPTION = 'T';

  // requests
  public static final int BIND_REQUEST = 'B';
  public static final int CLOSE_COMMAND =  'C';
  public static final int COPY_DATA_REQUEST = 'd';
  public static final int COPY_FAIL_REQUEST = 'f';
  public static final int DESCRIBE_REQUEST = 'D';
  public static final int EXECUTE_REQUEST = 'E';
  public static final int FAST_PATH_FUNCTION_CALL = 'F';
  public static final int FLUSH = 'H';
  public static final int PARSE_REQUEST = 'P';
  public static final int PASSWORD_REQUEST = 'p';
  public static final int PORTAL = 'P';
  public static final int QUERY_REQUEST = 'Q';
  public static final int SYNC_REQUEST = 'S';
  public static final int STATEMENT = 'S';
  public static final int TERMINATE_REQUEST = 'X';
}
