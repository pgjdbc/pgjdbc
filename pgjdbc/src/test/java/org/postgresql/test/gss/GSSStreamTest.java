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
}
