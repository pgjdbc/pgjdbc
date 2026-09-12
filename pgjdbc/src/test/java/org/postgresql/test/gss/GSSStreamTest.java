/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.gss;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.gss.GSSInputStream;
import org.postgresql.gss.GSSOutputStream;
import org.postgresql.test.util.StrangeInputStream;
import org.postgresql.test.util.StrangeOutputStream;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.internal.PgBufferedOutputStream;

import org.ietf.jgss.MessageProp;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Fails when a message does not survive a {@link GSSOutputStream} wrap followed by a
 * {@link GSSInputStream} unwrap, or when {@link GSSInputStream} accepts a declared packet
 * length the protocol does not allow.
 */
public class GSSStreamTest {
  static final boolean DEBUG = false;
  private final MessageProp messageProp = new MessageProp(0, true);

  /**
   * The test generates a random message, wraps it with {@link GSSOutputStream} and then unwraps
   * with {@link GSSInputStream}. The output should match the input.
   *
   * @throws Exception in case of error
   */
  @Test
  public void testGSSMessageBuffer() throws Exception {
    ByteArrayOutputStream wrappedContents = new ByteArrayOutputStream();
    Random rnd = new Random(42);
    MockGSSContext gssContext = new MockGSSContext(rnd.nextLong(), messageProp);
    GSSOutputStream gssOutputStream = new GSSOutputStream(
        new PgBufferedOutputStream(wrappedContents, 20),
        gssContext, messageProp, 20);
    byte[] testMessage = new byte[10240];
    if (DEBUG) {
      for (int i = 0; i < testMessage.length; i++) {
        testMessage[i] = (byte) i;
      }
    } else {
      rnd.nextBytes(testMessage);
    }
    try (StrangeOutputStream outputStream =
             new StrangeOutputStream(gssOutputStream, rnd.nextLong(), 0.1);) {
      outputStream.write(testMessage);
    }

    // Unwrap the contents
    // We use StrangeInputStream to test how GSSInputStream would react to the input streams
    // that produce incomplete reads, and to verify how GSSInputStream would respond to
    // reads of varying lengths.
    StrangeInputStream inputStream =
        new StrangeInputStream(
            rnd.nextLong(),
            new GSSInputStream(
                new StrangeInputStream(
                    rnd.nextLong(), new ByteArrayInputStream(wrappedContents.toByteArray())),
                gssContext, messageProp
            ));

    ByteArrayOutputStream unwrapResults = new ByteArrayOutputStream();
    int readBytes;
    byte[] tmpBuf = new byte[testMessage.length];
    while ((readBytes = inputStream.read(tmpBuf)) != -1) {
      unwrapResults.write(tmpBuf, 0, readBytes);
    }
    byte[] unwrapResult = unwrapResults.toByteArray();
    assertArrayEquals(testMessage, unwrapResult,
        "the message should be intact after wrap and unwrap");
  }

  /**
   * A four-byte length prefix, and nothing else. Enough to drive
   * {@code GSSInputStream.readLength}, which rejects the packet before any unwrap is
   * attempted.
   */
  private static byte[] lengthPrefix(int length) {
    byte[] wire = new byte[4];
    ByteConverter.int4(wire, 0, length);
    return wire;
  }

  private GSSInputStream gssInputStream(byte[] wire) {
    return new GSSInputStream(
        new ByteArrayInputStream(wire), new MockGSSContext(42, messageProp), messageProp);
  }

  /**
   * Fails when {@link GSSInputStream} accepts a declared packet length outside 1..16380.
   * libpq and the backend both cap a GSS encryption packet at
   * {@code PQ_GSS_MAX_PACKET_SIZE - sizeof(uint32)}, so a length outside that range means
   * the stream is desynced; before the cap the driver sized a fresh buffer from it.
   */
  @Test
  public void testRejectsPacketLengthOutsideTheProtocolRange() throws Exception {
    for (int length : new int[]{0, -1, Integer.MIN_VALUE, 16381, Integer.MAX_VALUE}) {
      GSSInputStream inputStream = gssInputStream(lengthPrefix(length));
      IOException thrown = assertThrows(IOException.class, inputStream::read,
          "a declared packet length of " + length + " must be rejected");
      assertTrue(thrown.getMessage().contains("invalid length"),
          "the failure should name the declared length as the problem: " + thrown.getMessage());
      assertTrue(thrown.getMessage().contains(String.valueOf(length)),
          "the failure should quote the declared length: " + thrown.getMessage());
    }
  }

  /**
   * Fails when {@link GSSInputStream} rejects a declared packet length of 16380, the upper
   * bound of the legal range. Without this case, widening the comparison to {@code >=}
   * would still pass {@link #testRejectsPacketLengthOutsideTheProtocolRange()}.
   */
  @Test
  public void testAcceptsTheLargestLegalPacketLength() throws Exception {
    GSSInputStream inputStream = gssInputStream(lengthPrefix(16380));
    // The payload never arrives, so the read reports end of stream. Reaching that point at
    // all is the assertion: the length was accepted.
    assertEquals(-1, inputStream.read(),
        "a packet of the largest legal length should be accepted");
  }
}
