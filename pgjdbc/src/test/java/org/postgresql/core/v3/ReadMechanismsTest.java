/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.CannedSocketFactory;
import org.postgresql.core.PGStream;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

class ReadMechanismsTest {

  /** The type byte of the message that follows the AuthenticationSASL body on the wire. */
  private static final byte NEXT_MESSAGE = 'E';

  private static PGStream stream(byte[] wire) throws IOException {
    return new PGStream(new CannedSocketFactory(wire), new HostSpec("localhost", 5432), 0, 8192);
  }

  /** The body followed by the type byte of the next message. */
  private static byte[] wire(byte[] body) {
    byte[] wire = Arrays.copyOf(body, body.length + 1);
    wire[body.length] = NEXT_MESSAGE;
    return wire;
  }

  /** Each name followed by its terminator, without the empty name that ends the list. */
  private static byte[] names(String... mechanisms) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (String mechanism : mechanisms) {
      byte[] name = mechanism.getBytes(StandardCharsets.UTF_8);
      out.write(name, 0, name.length);
      out.write(0);
    }
    return out.toByteArray();
  }

  /** A valid mechanism list: the names and the empty name that ends the list. */
  private static byte[] body(String... mechanisms) {
    byte[] names = names(mechanisms);
    return Arrays.copyOf(names, names.length + 1);
  }

  private static PSQLException assertRejected(byte[] body, String message, PSQLState state) {
    PSQLException e = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.readMechanisms(stream(wire(body)), body.length));
    assertEquals(message, e.getMessage());
    assertEquals(state.getState(), e.getSQLState());
    return e;
  }

  private static void assertInvalidList(byte[] body) {
    assertRejected(body,
        GT.tr("Received AuthenticationSASL message with invalid authentication mechanism list."),
        PSQLState.PROTOCOL_VIOLATION);
  }

  @Test
  void readsSingleMechanism() throws Exception {
    byte[] payload = body("SCRAM-SHA-256");
    assertEquals(Arrays.asList("SCRAM-SHA-256"),
        ConnectionFactoryImpl.readMechanisms(stream(wire(payload)), payload.length));
  }

  @Test
  void readsMultipleMechanisms() throws Exception {
    byte[] payload = body("OAUTHBEARER", "SCRAM-SHA-256", "SCRAM-SHA-256-PLUS");
    assertEquals(Arrays.asList("OAUTHBEARER", "SCRAM-SHA-256", "SCRAM-SHA-256-PLUS"),
        ConnectionFactoryImpl.readMechanisms(stream(wire(payload)), payload.length));
  }

  @Test
  void leavesTheNextMessageUnread() throws Exception {
    byte[] payload = body("SCRAM-SHA-256");
    PGStream stream = stream(wire(payload));

    ConnectionFactoryImpl.readMechanisms(stream, payload.length);

    assertEquals(NEXT_MESSAGE, stream.receiveChar(),
        "readMechanisms should consume exactly the body");
  }

  @Test
  void rejectsEmptyMechanismList() {
    assertRejected(new byte[]{0},
        GT.tr("Received AuthenticationSASL message with 0 mechanisms!"),
        PSQLState.PROTOCOL_VIOLATION);
  }

  @Test
  void rejectsBodyWithoutTerminator() {
    assertRejected("SCRAM-SHA-256".getBytes(StandardCharsets.UTF_8),
        GT.tr("Received invalid AuthenticationSASL message."),
        PSQLState.PROTOCOL_VIOLATION);
  }

  @Test
  void rejectsListWithoutTheEmptyNameThatEndsIt() {
    assertInvalidList(names("SCRAM-SHA-256"));
  }

  @Test
  void rejectsAnEmptyFirstName() {
    // \0OAUTHBEARER\0\0: the list ends at the first byte, so OAUTHBEARER follows the terminator.
    byte[] payload = new byte[1 + body("OAUTHBEARER").length];
    System.arraycopy(body("OAUTHBEARER"), 0, payload, 1, payload.length - 1);
    assertInvalidList(payload);
  }

  @Test
  void rejectsAMechanismAfterTheEndOfTheList() {
    // A\0\0B\0\0: B follows the empty name that ended the list.
    byte[] first = body("A");
    byte[] second = body("B");
    byte[] payload = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, payload, first.length, second.length);
    assertInvalidList(payload);
  }

  @Test
  void rejectsNonPositiveLength() {
    PSQLException e = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.readMechanisms(stream(wire(new byte[]{0})), 0));
    assertEquals(GT.tr("Received AuthenticationSASL message with an invalid length: {0}.", 0),
        e.getMessage());
    assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState());
  }
}
