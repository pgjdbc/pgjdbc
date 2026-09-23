/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.gss;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.postgresql.gss.GSSInputStream;
import org.postgresql.gss.GSSOutputStream;
import org.postgresql.test.util.StrangeInputStream;
import org.postgresql.test.util.StrangeOutputStream;
import org.postgresql.util.internal.PgBufferedOutputStream;

import org.ietf.jgss.MessageProp;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

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
   * {@link GSSOutputStream} inherits {@code writeZeros}. {@code write} sends 100 bytes or more
   * written into an empty buffer straight from the caller's array, so the data goes in as 3 bytes
   * and then 97: the 97-byte write fills the buffer exactly, {@code write} sends it and leaves the
   * 0xff bytes in the array, and {@code writeZeros} starts on an empty buffer that is not zeroed.
   * It used to send those bytes in place of the zeros.
   */
  @Test
  void writeZerosAfterDataEndingAtBufferBoundarySendsZeros() throws Exception {
    ByteArrayOutputStream wrappedContents = new ByteArrayOutputStream();
    MockGSSContext gssContext = new MockGSSContext(0, messageProp);
    // MockGSSContext.getWrapSizeLimit returns 100, so the GSSOutputStream buffer holds 100 bytes
    GSSOutputStream out = new GSSOutputStream(
        new PgBufferedOutputStream(wrappedContents, 20), gssContext, messageProp, 20);
    byte[] data = new byte[100];
    Arrays.fill(data, (byte) 0xff);
    out.write(data, 0, 3);
    out.write(data, 3, 97);

    out.writeZeros(103);
    out.flush();

    byte[] expected = new byte[203];
    Arrays.fill(expected, 0, 100, (byte) 0xff);
    assertArrayEquals(expected, unwrap(wrappedContents.toByteArray(), gssContext),
        "100 bytes of 0xff written as 3 + 97, then writeZeros(103)");
  }

  private byte[] unwrap(byte[] wrapped, MockGSSContext gssContext) throws IOException {
    GSSInputStream in =
        new GSSInputStream(new ByteArrayInputStream(wrapped), gssContext, messageProp);
    ByteArrayOutputStream unwrapped = new ByteArrayOutputStream();
    byte[] tmp = new byte[256];
    int n;
    while ((n = in.read(tmp)) != -1) {
      unwrapped.write(tmp, 0, n);
    }
    return unwrapped.toByteArray();
  }
}
