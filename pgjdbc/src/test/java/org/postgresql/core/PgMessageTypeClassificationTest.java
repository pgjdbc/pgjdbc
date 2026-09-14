/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fails when a {@link PgMessageType} constant is added without being classified as a backend
 * message or a frontend message.
 *
 * <p>Every constant appears exactly once, either in {@link #BACKEND} or in {@link #FRONTEND}.
 * A backend message is one the driver reads: its reader takes the type byte through
 * {@link PGStream#receiveMessageType()}, opens the message through
 * {@link PGStream#readMessageLength(String, int, int)},
 * {@link PGStream#readFixedMessageLength(String, int)}, or
 * {@link PGStream#readPreAuthMessageLength(String, int, int)}, checks any further length it reads
 * from the body, such as a count or a field length, against the bytes the message has left, and
 * closes it with {@link PGStream#endMessage()}. A frontend message is one the driver only writes; the
 * Describe and Close targets {@link PgMessageType#PORTAL} and {@link PgMessageType#STATEMENT}
 * count as frontend too. CopyData and CopyDone travel in both directions and are listed as
 * backend, because the driver reads them.</p>
 *
 * <p>{@link PGStream#receiveMessageType()} detects a reader that skips {@code endMessage} only
 * when a test drives that message, so a new backend type whose reader no test exercises would go
 * unnoticed. Constants are found by reflection rather than by byte value, because one byte names
 * both a backend and a frontend message ({@code 'S'} is Sync and ParameterStatus).</p>
 */
class PgMessageTypeClassificationTest {
  /** Messages the backend sends and the driver reads through the bounded message API. */
  private static final List<String> BACKEND = Arrays.asList(
      "AUTHENTICATION_RESPONSE",
      "PARAMETER_STATUS_RESPONSE",
      "BACKEND_KEY_DATA_RESPONSE",
      "READY_FOR_QUERY_RESPONSE",
      "ROW_DESCRIPTION_RESPONSE",
      "DATA_ROW_RESPONSE",
      "COMMAND_COMPLETE_RESPONSE",
      "COPY_OUT_RESPONSE",
      "COPY_BOTH_RESPONSE",
      "COPY_IN_RESPONSE",
      "NEGOTIATE_PROTOCOL_RESPONSE",
      "ERROR_RESPONSE",
      "EMPTY_QUERY_RESPONSE",
      "ASYNCHRONOUS_NOTICE",
      "NOTICE_RESPONSE",
      "PARSE_COMPLETE_RESPONSE",
      "BIND_COMPLETE_RESPONSE",
      "CLOSE_COMPLETE_RESPONSE",
      "NO_DATA_RESPONSE",
      "PORTAL_SUSPENDED_RESPONSE",
      "PARAMETER_DESCRIPTION_RESPONSE",
      "FUNCTION_CALL_RESPONSE",
      "COPY_DONE",
      "COPY_DATA"
  );

  /** Messages, and Describe or Close targets, that the driver only writes. */
  private static final List<String> FRONTEND = Arrays.asList(
      "BIND",
      "CLOSE_REQUEST",
      "DESCRIBE_REQUEST",
      "EXECUTE_REQUEST",
      "FUNCTION_CALL_REQ",
      "FLUSH_REQ",
      "PARSE_REQUEST",
      "QUERY_REQUEST",
      "SYNC_REQUEST",
      "TERMINATE_REQUEST",
      "COPY_FAIL",
      "GSS_TOKEN_REQUEST",
      "PASSWORD_REQUEST",
      "SASL_RESPONSE",
      "SASL_INITIAL_RESPONSE",
      "PORTAL",
      "STATEMENT"
  );

  private static final String HOW_TO_CLASSIFY =
      "Add each new PgMessageType constant to exactly one list in PgMessageTypeClassificationTest."
          + " List it in BACKEND if the driver reads it: the reader takes the type through"
          + " PGStream.receiveMessageType, opens the message through readMessageLength,"
          + " readFixedMessageLength, or readPreAuthMessageLength, checks any further length it"
          + " reads from the body (a count, a field length) against the bytes the message has left,"
          + " and closes it with endMessage."
          + " List it in FRONTEND if the driver only writes it.";

  /**
   * Names of the static {@code byte} fields {@link PgMessageType} declares, whatever their access
   * modifier and whether or not they are final.
   */
  private static Set<String> declaredConstants() {
    Set<String> names = new LinkedHashSet<>();
    for (Field field : PgMessageType.class.getDeclaredFields()) {
      int modifiers = field.getModifiers();
      if (Modifier.isStatic(modifiers) && field.getType() == byte.class) {
        names.add(field.getName());
      }
    }
    return names;
  }

  private static List<String> classified() {
    List<String> all = new ArrayList<>(BACKEND);
    all.addAll(FRONTEND);
    return all;
  }

  @Test
  void everyDeclaredConstantIsClassified() {
    List<String> unclassified = new ArrayList<>(declaredConstants());
    unclassified.removeAll(classified());

    assertEquals(Collections.emptyList(), unclassified,
        "PgMessageType constants in neither BACKEND nor FRONTEND. " + HOW_TO_CLASSIFY);
  }

  @Test
  void noConstantIsClassifiedTwice() {
    Set<String> seen = new LinkedHashSet<>();
    Set<String> repeated = new LinkedHashSet<>();
    for (String name : classified()) {
      if (!seen.add(name)) {
        repeated.add(name);
      }
    }

    assertEquals(Collections.emptySet(), repeated,
        "Names listed more than once across BACKEND and FRONTEND. " + HOW_TO_CLASSIFY);
  }

  @Test
  void everyClassifiedNameIsDeclared() {
    List<String> undeclared = classified();
    undeclared.removeAll(declaredConstants());

    assertEquals(Collections.emptyList(), undeclared,
        "Names in BACKEND or FRONTEND that PgMessageType no longer declares; remove them from the"
            + " list.");
  }
}
