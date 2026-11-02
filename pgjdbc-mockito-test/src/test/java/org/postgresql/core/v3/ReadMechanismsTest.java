/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.postgresql.core.Encoding;
import org.postgresql.core.PGStream;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

class ReadMechanismsTest {

  private static PGStream stream(byte[] wire) throws IOException {
    PGStream stream = mock(PGStream.class);
    when(stream.getEncoding()).thenReturn(Encoding.getJVMEncoding("UTF-8"));
    when(stream.receive(anyInt())).thenAnswer(invocation -> {
      int size = invocation.getArgument(0);
      assertTrue(size <= wire.length, "read " + size + " bytes past the end of the message");
      return Arrays.copyOfRange(wire, 0, size);
    });
    return stream;
  }

  private static byte[] body(String... mechanisms) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (String mechanism : mechanisms) {
      byte[] name = mechanism.getBytes(StandardCharsets.UTF_8);
      out.write(name, 0, name.length);
      out.write(0);
    }
    out.write(0);
    return out.toByteArray();
  }

  @Test
  void readsSingleMechanism() throws Exception {
    byte[] payload = body("SCRAM-SHA-256");
    assertEquals(Arrays.asList("SCRAM-SHA-256"),
        ConnectionFactoryImpl.readMechanisms(stream(payload), payload.length));
  }

  @Test
  void readsMultipleMechanisms() throws Exception {
    byte[] payload = body("OAUTHBEARER", "SCRAM-SHA-256", "SCRAM-SHA-256-PLUS");
    assertEquals(Arrays.asList("OAUTHBEARER", "SCRAM-SHA-256", "SCRAM-SHA-256-PLUS"),
        ConnectionFactoryImpl.readMechanisms(stream(payload), payload.length));
  }

  @Test
  void stopsAtTheEndOfTheMessage() throws Exception {
    byte[] payload = body("SCRAM-SHA-256");
    byte[] wire = Arrays.copyOf(payload, payload.length + 1);
    wire[payload.length] = 'E'; // Next message

    PGStream stream = stream(wire);
    assertEquals(Arrays.asList("SCRAM-SHA-256"),
        ConnectionFactoryImpl.readMechanisms(stream, payload.length));
    // Exactly the body was consumed, so the next message type byte is left for the caller
    verify(stream).receive(payload.length);
  }

  @Test
  void rejectsEmptyMechanismList() throws Exception {
    PSQLException e = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.readMechanisms(stream(new byte[]{0}), 1));
    assertEquals(GT.tr("Received AuthenticationSASL message with 0 mechanisms!"),
        e.getMessage());
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), e.getSQLState());
  }

  @Test
  void rejectsBodyWithoutTerminator() throws Exception {
    byte[] payload = "SCRAM-SHA-256".getBytes(StandardCharsets.UTF_8);
    PSQLException e = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.readMechanisms(stream(payload), payload.length));
    assertEquals(GT.tr("Received invalid AuthenticationSASL message."), e.getMessage());
    assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState());
  }

  @Test
  void rejectsUnterminatedMechanismName() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] name = "SCRAM-SHA-256".getBytes(StandardCharsets.UTF_8);
    out.write(name, 0, name.length);
    out.write(0);
    byte[] payload = out.toByteArray();

    PSQLException e = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.readMechanisms(stream(payload), payload.length));
    assertEquals(
        GT.tr("Received AuthenticationSASL message with invalid authentication mechanism list."),
        e.getMessage());
    assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState());
  }

  @Test
  void rejectsNonPositiveLength() throws Exception {
    PSQLException e = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.readMechanisms(stream(new byte[]{0}), 0));
    assertEquals(GT.tr("Received AuthenticationSASL message with an invalid length: {0}.", 0),
        e.getMessage());
    assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState());
  }
}
