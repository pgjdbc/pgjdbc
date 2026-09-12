/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Fails when a backend message type is added without a hardened reader.
 *
 * <p>Every backend reader must declare a message envelope: read the length through
 * {@code PGStream.readMessageLength} (or {@code readFixedMessageLength} /
 * {@code readPreAuthMessageLength}), check any further length it reads from the body against the
 * bytes the envelope has left, and close the envelope with {@code endMessage}. A reader that
 * skips this leaves the stream off a message boundary, which is the desync class of bug issue
 * #4015 reported.</p>
 *
 * <p>{@link PGStream#receiveMessageType()} catches such a reader at run time, but only
 * after a test drives that message. A new message type with no test would slip through.
 * This test closes that gap. It lists every constant in {@link PgMessageType} in one of
 * two sets: {@link #HARDENED} for backend messages the driver reads, and {@link #FRONTEND}
 * for messages the driver only sends. Add a constant to {@link PgMessageType} and this
 * test fails until you classify it. The failure message states what a backend reader
 * owes.</p>
 */
class BackendMessageInventoryTest {

  /** Backend message types whose reader declares and closes its envelope. */
  private static final Set<String> HARDENED = new LinkedHashSet<>(Arrays.asList(
      "ASYNCHRONOUS_NOTICE",
      "AUTHENTICATION_RESPONSE",
      "BACKEND_KEY_DATA_RESPONSE",
      "BIND_COMPLETE_RESPONSE",
      "CLOSE_COMPLETE_RESPONSE",
      "COMMAND_COMPLETE_RESPONSE",
      "COPY_BOTH_RESPONSE",
      "COPY_DATA",
      "COPY_DONE",
      "COPY_IN_RESPONSE",
      "COPY_OUT_RESPONSE",
      "DATA_ROW_RESPONSE",
      "EMPTY_QUERY_RESPONSE",
      "ERROR_RESPONSE",
      "FUNCTION_CALL_RESPONSE",
      "NEGOTIATE_PROTOCOL_RESPONSE",
      "NO_DATA_RESPONSE",
      "NOTICE_RESPONSE",
      "PARAMETER_DESCRIPTION_RESPONSE",
      "PARAMETER_STATUS_RESPONSE",
      "PARSE_COMPLETE_RESPONSE",
      "PORTAL_SUSPENDED_RESPONSE",
      "READY_FOR_QUERY_RESPONSE",
      "ROW_DESCRIPTION_RESPONSE"
  ));

  /**
   * Message types the driver sends rather than reads, so no envelope applies. Frontend
   * messages carry a length the driver writes itself.
   */
  private static final Set<String> FRONTEND = new LinkedHashSet<>(Arrays.asList(
      "BIND",
      "CLOSE_REQUEST",
      "COPY_FAIL",
      "DESCRIBE_REQUEST",
      "EXECUTE_REQUEST",
      "FLUSH_REQ",
      "FUNCTION_CALL_REQ",
      "GSS_TOKEN_REQUEST",
      "PARSE_REQUEST",
      "PASSWORD_REQUEST",
      "PORTAL",
      "QUERY_REQUEST",
      "SASL_INITIAL_RESPONSE",
      "SASL_RESPONSE",
      "STATEMENT",
      "SYNC_REQUEST",
      "TERMINATE_REQUEST"
  ));

  @Test
  void everyMessageTypeIsClassified() {
    Set<String> declared = Arrays.stream(PgMessageType.class.getDeclaredFields())
        .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == byte.class)
        .map(Field::getName)
        .collect(Collectors.toCollection(TreeSet::new));

    Set<String> unclassified = new TreeSet<>(declared);
    unclassified.removeAll(HARDENED);
    unclassified.removeAll(FRONTEND);

    assertEquals(java.util.Collections.emptySet(), unclassified,
        "PgMessageType declares a message this test does not know about. A backend message"
            + " needs a reader that reads its length through PGStream.readMessageLength (or"
            + " readFixedMessageLength / readPreAuthMessageLength), checks any further length it"
            + " reads from the body against the bytes the envelope has left, and closes the"
            + " envelope with endMessage; then list it in HARDENED. A message the driver only"
            + " sends belongs in FRONTEND.");

    Set<String> stale = new TreeSet<>(HARDENED);
    stale.addAll(FRONTEND);
    stale.removeAll(declared);
    assertEquals(java.util.Collections.emptySet(), stale,
        "This test lists a message PgMessageType no longer declares");
  }

  @Test
  void hardenedAndFrontendDoNotOverlap() {
    Set<String> both = new TreeSet<>(HARDENED);
    both.retainAll(FRONTEND);
    assertEquals(java.util.Collections.emptySet(), both,
        "A message type is either read from the backend or sent to it");
  }
}
