/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.CannedSocketFactory;
import org.postgresql.core.PGStream;
import org.postgresql.util.ByteStreamWriter;
import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A payload of any length reaches the backend as CopyData messages that carry its bytes in order,
 * each with a payload of at most the limit, and with no empty message.
 *
 * <p>Most cases use a limit of 3 bytes so that a split is visible in a short payload. The
 * messages are read back from the bytes {@link PGStream} wrote to a {@link CannedSocketFactory}
 * socket and printed as their payloads, so {@code [abc, d]} is two messages.</p>
 */
class CopyDataOutputStreamTest {
  private static final int LIMIT = 3;

  private final CannedSocketFactory socketFactory = new CannedSocketFactory(new byte[0]);
  private final PGStream pgStream;

  CopyDataOutputStreamTest() throws IOException {
    pgStream = new PGStream(socketFactory, new HostSpec("localhost", 5432), 0, 8192);
  }

  private CopyDataOutputStream stream(int length) {
    return new CopyDataOutputStream(pgStream, length, LIMIT);
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  /**
   * Returns the payloads of the CopyData messages written so far, with each zero byte shown as
   * {@code 0}.
   */
  private List<String> sentMessages() throws IOException {
    pgStream.flush();
    byte[] written = socketFactory.getWritten();
    List<String> messages = new ArrayList<>();
    int pos = 0;
    while (pos < written.length) {
      assertEquals('d', (char) written[pos], "message type at offset " + pos);
      int length = readInt4(written, pos + 1);
      StringBuilder payload = new StringBuilder();
      for (int i = pos + 5; i < pos + 1 + length; i++) {
        payload.append(written[i] == 0 ? '0' : (char) written[i]);
      }
      messages.add(payload.toString());
      pos += 1 + length;
    }
    assertEquals(written.length, pos, "end of the last message");
    return messages;
  }

  private static int readInt4(byte[] buf, int pos) {
    return (buf[pos] & 0xff) << 24 | (buf[pos + 1] & 0xff) << 16 | (buf[pos + 2] & 0xff) << 8
        | buf[pos + 3] & 0xff;
  }

  private static ByteStreamWriter writer(int length, WriteAction action) {
    return new ByteStreamWriter() {
      @Override
      public int getLength() {
        return length;
      }

      @Override
      public void writeTo(ByteStreamTarget target) throws IOException {
        action.write(target.getOutputStream());
      }
    };
  }

  private interface WriteAction {
    void write(OutputStream out) throws IOException;
  }

  @Test
  void aPayloadOfExactlyTheLimitIsOneMessage() throws IOException {
    CopyDataOutputStream out = stream(3);
    out.write(ascii("abc"), 0, 3);
    out.finish();
    assertEquals("[abc]", sentMessages().toString());
  }

  @Test
  void aPayloadOneByteOverTheLimitIsTwoMessages() throws IOException {
    CopyDataOutputStream out = stream(4);
    out.write(ascii("abcd"), 0, 4);
    out.finish();
    assertEquals("[abc, d]", sentMessages().toString());
  }

  @Test
  void aPayloadOfTwiceTheLimitEndsWithoutAnEmptyMessage() throws IOException {
    CopyDataOutputStream out = stream(6);
    out.write(ascii("abcdef"), 0, 6);
    out.finish();
    assertEquals("[abc, def]", sentMessages().toString());
  }

  @Test
  void anEmptyPayloadSendsNoMessage() throws IOException {
    CopyDataOutputStream out = stream(0);
    out.write(new byte[0], 0, 0);
    out.finish();
    assertEquals(0, sentMessages().size(), "number of messages sent");
  }

  @Test
  void anOffsetSelectsTheBytesThatFollowIt() throws IOException {
    CopyDataOutputStream out = stream(5);
    out.write(ascii("abcdefgh"), 2, 5);
    out.finish();
    assertEquals("[cde, fg]", sentMessages().toString());
  }

  @Test
  void aWriteThatStartsInsideAMessageContinuesInTheNext() throws IOException {
    CopyDataOutputStream out = stream(6);
    out.write(ascii("ab"), 0, 2);
    out.write(ascii("cdef"), 0, 4);
    out.finish();
    assertEquals("[abc, def]", sentMessages().toString());
  }

  @Test
  void singleByteWritesAreSplitAtTheLimit() throws IOException {
    CopyDataOutputStream out = stream(7);
    for (byte b : ascii("abcdefg")) {
      out.write(b);
    }
    out.finish();
    assertEquals("[abc, def, g]", sentMessages().toString());
  }

  @Test
  void finishPadsAShortPayloadWithZerosAcrossMessages() throws IOException {
    CopyDataOutputStream out = stream(7);
    out.write(ascii("ab"), 0, 2);
    out.finish();
    assertEquals("[ab0, 000, 0]", sentMessages().toString());
  }

  @Test
  void aWritePastTheLengthThrowsAndSendsNoneOfItsBytes() throws IOException {
    CopyDataOutputStream out = stream(4);
    out.write(ascii("ab"), 0, 2);
    IOException e = assertThrows(IOException.class, () -> out.write(ascii("cde"), 0, 3));
    assertEquals("Attempt to write more than the specified 4 bytes", e.getMessage());
    out.finish();
    assertEquals("[ab0, 0]", sentMessages().toString());
  }

  @Test
  void aSingleByteWritePastTheLengthThrows() throws IOException {
    CopyDataOutputStream out = stream(1);
    out.write('a');
    assertThrows(IOException.class, () -> out.write('b'));
    out.finish();
    assertEquals("[a]", sentMessages().toString());
  }

  @Test
  void aNegativeLengthIsRefusedBeforeAnythingIsSent() throws IOException {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> stream(-1));
    assertEquals("length must be non-negative, got -1", e.getMessage());
    assertEquals(0, sentMessages().size(), "number of messages sent");
  }

  @Test
  void anArrayIsSplitAtTheLimit() throws IOException {
    stream(5).writeAndFinish(ascii("abcde"), 0);
    assertEquals("[abc, de]", sentMessages().toString());
  }

  @Test
  void anArrayLongerThanTheLengthSendsOnlyTheLength() throws IOException {
    stream(4).writeAndFinish(ascii("xabcdef"), 1);
    assertEquals("[abc, d]", sentMessages().toString());
  }

  @Test
  void anArrayShorterThanTheLengthIsPaddedWithZeros() throws IOException {
    stream(5).writeAndFinish(ascii("xab"), 1);
    assertEquals("[ab0, 00]", sentMessages().toString());
  }

  @Test
  void aWriterIsSplitAtTheLimit() throws IOException {
    stream(5).writeAndFinish(writer(5, out -> out.write(ascii("abcde"))));
    assertEquals("[abc, de]", sentMessages().toString());
  }

  @Test
  void aWriterThatWritesSingleBytesIsSplitAtTheLimit() throws IOException {
    stream(4).writeAndFinish(writer(4, out -> {
      for (byte b : ascii("abcd")) {
        out.write(b);
      }
    }));
    assertEquals("[abc, d]", sentMessages().toString());
  }

  @Test
  void aWriterThatWritesLessThanItsLengthIsPaddedWithZeros() throws IOException {
    stream(5).writeAndFinish(writer(5, out -> out.write(ascii("a"))));
    assertEquals("[a00, 00]", sentMessages().toString());
  }

  @Test
  void aWriterThatWritesMoreThanItsLengthFails() {
    IOException e = assertThrows(IOException.class,
        () -> stream(2).writeAndFinish(writer(2, out -> out.write(ascii("abc")))));
    assertEquals("Attempt to write more than the specified 2 bytes", e.getMessage());
  }

  @Test
  void aRuntimeExceptionFromTheWriterIsReportedAsAnIOException() {
    IllegalStateException cause = new IllegalStateException("source failed");
    IOException e = assertThrows(IOException.class,
        () -> stream(2).writeAndFinish(writer(2, out -> {
          throw cause;
        })));
    assertSame(cause, e.getCause());
  }

  /**
   * PostgreSQL 14 and later reject a CopyData message whose length field, which counts its own
   * 4 bytes, exceeds {@code MaxAllocSize - 1}.
   */
  @Test
  void theLimitFitsTheLargestMessageTheServerAccepts() {
    int maxAllocSize = 0x3fffffff;
    assertTrue(CopyDataOutputStream.MAX_PAYLOAD_LENGTH + 4 <= maxAllocSize - 1,
        "MAX_PAYLOAD_LENGTH + 4 <= MaxAllocSize - 1, MAX_PAYLOAD_LENGTH = "
            + CopyDataOutputStream.MAX_PAYLOAD_LENGTH);
  }

  /**
   * A length of {@code Integer.MAX_VALUE} opens a message of the largest payload, so the length
   * field does not overflow.
   */
  @Test
  void aLengthOfIntegerMaxValueOpensAMessageOfTheLargestPayload() throws IOException {
    CopyDataOutputStream out = new CopyDataOutputStream(pgStream, Integer.MAX_VALUE,
        CopyDataOutputStream.MAX_PAYLOAD_LENGTH);
    out.write('a');
    pgStream.flush();
    byte[] written = socketFactory.getWritten();
    assertEquals(CopyDataOutputStream.MAX_PAYLOAD_LENGTH + 4, readInt4(written, 1),
        "length field of the first message");
  }
}
