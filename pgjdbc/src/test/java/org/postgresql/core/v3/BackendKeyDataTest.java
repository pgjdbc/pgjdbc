/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.postgresql.test.util.PgWire.concat;
import static org.postgresql.test.util.PgWire.filler;
import static org.postgresql.test.util.PgWire.int4;
import static org.postgresql.test.util.PgWire.messageWithLength;
import static org.postgresql.test.util.PgWire.readyForQuery;

import org.postgresql.core.PGStream;
import org.postgresql.core.ProtocolVersion;
import org.postgresql.test.util.InMemorySocketFactory;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Properties;

/**
 * Fails when the BackendKeyData reader accepts a cancel key the protocol version forbids.
 *
 * <p>Protocol 3.0 fixes the key at 4 bytes; 3.2 allows up to 256, bounded by the declared message
 * length rather than by a per-version check. The reader sits in
 * {@link QueryExecutorImpl#readStartupMessages()}, which the constructor calls, so a canned byte
 * script reaches it with no server, and no connection, involved.</p>
 */
class BackendKeyDataTest {

  private static final int PID = 4711;

  /**
   * A BackendKeyData message carrying a cancel key of {@code keyLength} bytes. The declared
   * length is passed separately so a test can declare one length and send another.
   */
  private static byte[] backendKeyData(int declaredLength, int keyLength) {
    return messageWithLength('K', declaredLength, int4(PID), filler(keyLength));
  }

  private static PGStream newStream(ProtocolVersion protocolVersion, byte[] script)
      throws IOException {
    PGStream pgStream = new PGStream(
        new InMemorySocketFactory(script), new HostSpec("localhost", 1), 0, 8192);
    pgStream.setProtocolVersion(protocolVersion);
    return pgStream;
  }

  private static QueryExecutorImpl newExecutor(PGStream pgStream)
      throws IOException, java.sql.SQLException {
    return new QueryExecutorImpl(pgStream, 0, new Properties());
  }

  @Test
  void v30RejectsACancelKeyThatIsNotFourBytes() throws Exception {
    // Protocol 3.0 fixes the cancel key at 4 bytes. Anything else means the driver and the
    // backend disagree about the message layout, and the key that reaches setBackendKeyData
    // would not cancel anything.
    for (int keyLength : new int[]{0, 3, 5, 256}) {
      PGStream pgStream = newStream(ProtocolVersion.v3_0,
          backendKeyData(8 + keyLength, keyLength));

      PSQLException thrown = assertThrows(PSQLException.class, () -> newExecutor(pgStream),
          "a " + keyLength + "-byte cancel key must be refused under protocol 3.0");
      assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), thrown.getSQLState());
      // This message has translations, so assert on what survives them: the protocol
      // version it names, not the English wording around it.
      assertTrue(thrown.getMessage().contains("3.0"),
          "the failure should name the protocol version whose rule was broken: "
              + thrown.getMessage());
      assertTrue(pgStream.isClosed(),
          "a session whose cancel key was refused must not be handed to the caller");
    }
  }

  @Test
  void v30AcceptsAFourByteCancelKey() throws Exception {
    PGStream pgStream = newStream(ProtocolVersion.v3_0,
        concat(backendKeyData(12, 4), readyForQuery('I')));

    QueryExecutorImpl executor = newExecutor(pgStream);
    assertEquals(PID, executor.getBackendPID());
  }

  @Test
  void v32AcceptsTheLargestCancelKey() throws Exception {
    // Protocol 3.2 widened the cancel key to at most 256 bytes. The bound belongs to the
    // length prefix now, so this case pins that the largest legal key still gets through.
    PGStream pgStream = newStream(ProtocolVersion.v3_2,
        concat(backendKeyData(264, 256), readyForQuery('I')));

    QueryExecutorImpl executor = newExecutor(pgStream);
    assertEquals(PID, executor.getBackendPID());
  }

  @Test
  void aCancelKeyBeyondTheProtocolMaximumIsRefused() throws Exception {
    // 265 declares a 257-byte key, one past what protocol 3.2 allows. This is the check
    // that makes a separate per-version length bound unnecessary, so it needs a test of its
    // own: without it, nothing covers the upper end of the cancel key range at all.
    PGStream pgStream = newStream(ProtocolVersion.v3_2, backendKeyData(265, 257));

    IOException thrown = assertThrows(IOException.class, () -> newExecutor(pgStream),
        "a cancel key past the protocol maximum must be refused");
    assertTrue(thrown.getMessage().contains("BackendKeyData"),
        "the failure should name the message: " + thrown.getMessage());
    assertTrue(thrown.getMessage().contains("264"),
        "the failure should quote the maximum it enforces: " + thrown.getMessage());
    assertTrue(pgStream.isClosed(),
        "an over-long BackendKeyData must mark the stream broken");
  }
}
