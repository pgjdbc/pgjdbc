/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.postgresql.core.EncodingPredictor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ServerErrorMessage implements Serializable {
  private static final Logger LOGGER = Logger.getLogger(ServerErrorMessage.class.getName());

  private static final Character SEVERITY = 'S';
  private static final Character MESSAGE = 'M';
  private static final Character DETAIL = 'D';
  private static final Character HINT = 'H';
  private static final Character POSITION = 'P';
  private static final Character WHERE = 'W';
  private static final Character FILE = 'F';
  private static final Character LINE = 'L';
  private static final Character ROUTINE = 'R';
  private static final Character SQLSTATE = 'C';
  private static final Character INTERNAL_POSITION = 'p';
  private static final Character INTERNAL_QUERY = 'q';
  private static final Character SCHEMA = 's';
  private static final Character TABLE = 't';
  private static final Character COLUMN = 'c';
  private static final Character DATATYPE = 'd';
  private static final Character CONSTRAINT = 'n';

  private final Map<Character, String> mesgParts = new HashMap<Character, String>();

  public ServerErrorMessage(EncodingPredictor.DecodeResult serverError) {
    this(serverError.result);
    if (serverError.encoding != null) {
      mesgParts.put(MESSAGE, mesgParts.get(MESSAGE)
          + GT.tr(" (pgjdbc: autodetected server-encoding to be {0}, if the message is not readable, please check database logs and/or host, port, dbname, user, password, pg_hba.conf)",
          serverError.encoding)
      );
    }
  }

  public ServerErrorMessage(String serverError) {
    char[] chars = serverError.toCharArray();
    int pos = 0;
    int length = chars.length;
    while (pos < length) {
      char mesgType = chars[pos];
      if (mesgType != '\0') {
        pos++;
        int startString = pos;
        // order here is important position must be checked before accessing the array
        while (pos < length && chars[pos] != '\0') {
          pos++;
        }
        String mesgPart = new String(chars, startString, pos - startString);
        mesgParts.put(mesgType, mesgPart);
      }
      pos++;
    }
  }

  public @Nullable String getSQLState() {
    return mesgParts.get(SQLSTATE);
  }

  public @Nullable String getMessage() {
    return mesgParts.get(MESSAGE);
  }

  public @Nullable String getSeverity() {
    return mesgParts.get(SEVERITY);
  }

  public @Nullable String getDetail() {
    return mesgParts.get(DETAIL);
  }

  public @Nullable String getHint() {
    return mesgParts.get(HINT);
  }

  public int getPosition() {
    return getIntegerPart(POSITION);
  }

  public @Nullable String getWhere() {
    return mesgParts.get(WHERE);
  }

  public @Nullable String getSchema() {
    return mesgParts.get(SCHEMA);
  }

  public @Nullable String getTable() {
    return mesgParts.get(TABLE);
  }

  public @Nullable String getColumn() {
    return mesgParts.get(COLUMN);
  }

  public @Nullable String getDatatype() {
    return mesgParts.get(DATATYPE);
  }

  public @Nullable String getConstraint() {
    return mesgParts.get(CONSTRAINT);
  }

  public @Nullable String getFile() {
    return mesgParts.get(FILE);
  }

  public int getLine() {
    return getIntegerPart(LINE);
  }

  public @Nullable String getRoutine() {
    return mesgParts.get(ROUTINE);
  }

  public @Nullable String getInternalQuery() {
    return mesgParts.get(INTERNAL_QUERY);
  }

  public int getInternalPosition() {
    return getIntegerPart(INTERNAL_POSITION);
  }

  private int getIntegerPart(Character c) {
    String s = mesgParts.get(c);
    if (s == null) {
      return 0;
    }
    return Integer.parseInt(s);
  }

  String getNonSensitiveErrorMessage() {
    StringBuilder totalMessage = new StringBuilder();
    String message = mesgParts.get(SEVERITY);
    if (message != null) {
      totalMessage.append(message).append(": ");
    }
    message = mesgParts.get(MESSAGE);
    if (message != null) {
      totalMessage.append(message);
    }
    return totalMessage.toString();
  }

  public String toString() {
    // Now construct the message from what the server sent
    // The general format is:
    // SEVERITY: Message \n
    // Detail: \n
    // Hint: \n
    // Position: \n
    // Where: \n
    // Internal Query: \n
    // Internal Position: \n
    // Location: File:Line:Routine \n
    // SQLState: \n
    //
    // Normally only the message and detail is included.
    // If INFO level logging is enabled then detail, hint, position and where are
    // included. If DEBUG level logging is enabled then all information
    // is included.

    StringBuilder totalMessage = new StringBuilder();
    String message = mesgParts.get(SEVERITY);
    if (message != null) {
      totalMessage.append(message).append(": ");
    }
    message = mesgParts.get(MESSAGE);
    if (message != null) {
      totalMessage.append(message);
    }
    message = mesgParts.get(DETAIL);
    if (message != null) {
      totalMessage.append("\n  ").append(GT.tr("Detail: {0}", message));
    }

    message = mesgParts.get(HINT);
    if (message != null) {
      totalMessage.append("\n  ").append(GT.tr("Hint: {0}", message));
    }
    message = mesgParts.get(POSITION);
    if (message != null) {
      totalMessage.append("\n  ").append(GT.tr("Position: {0}", message));
    }
    message = mesgParts.get(WHERE);
    if (message != null) {
      totalMessage.append("\n  ").append(GT.tr("Where: {0}", message));
    }

    if (LOGGER.isLoggable(Level.FINEST)) {
      String internalQuery = mesgParts.get(INTERNAL_QUERY);
      if (internalQuery != null) {
        totalMessage.append("\n  ").append(GT.tr("Internal Query: {0}", internalQuery));
      }
      String internalPosition = mesgParts.get(INTERNAL_POSITION);
      if (internalPosition != null) {
        totalMessage.append("\n  ").append(GT.tr("Internal Position: {0}", internalPosition));
      }

      String file = mesgParts.get(FILE);
      String line = mesgParts.get(LINE);
      String routine = mesgParts.get(ROUTINE);
      if (file != null || line != null || routine != null) {
        totalMessage.append("\n  ").append(GT.tr("Location: File: {0}, Routine: {1}, Line: {2}",
            file, routine, line));
      }
      message = mesgParts.get(SQLSTATE);
      if (message != null) {
        totalMessage.append("\n  ").append(GT.tr("Server SQLState: {0}", message));
      }
    }

    return totalMessage.toString();
  }
}
