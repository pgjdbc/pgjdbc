/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Boundary tests for the message length check and for the column lengths inside a DataRow, driven
 * from a byte array rather than a server.
 */
@Isolated("Uses Locale.setDefault")
class BackendMessageLengthTest {

  // The assertions match on message text, which GT.tr translates once these strings are
  // localized.
  private static Locale defaultLocale;

  @BeforeAll
  static void useRootLocale() {
    defaultLocale = Locale.getDefault();
    Locale.setDefault(Locale.ROOT);
  }

  @AfterAll
  static void restoreLocale() {
    Locale.setDefault(defaultLocale);
  }

  private static PGStream streamOf(byte[] bytes) throws IOException {
    return new PGStream(new CannedSocketFactory(bytes), new HostSpec("localhost", 5432), 0, 8192);
  }

  private static byte[] int4(int... values) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int value : values) {
      out.write(value >>> 24);
      out.write(value >>> 16);
      out.write(value >>> 8);
      out.write(value);
    }
    return out.toByteArray();
  }

  @Test
  void rejectsLengthsOutsideTheRange() throws IOException {
    int max = PGStream.MAX_SMALL_MESSAGE_LENGTH;
    int[] rejected = {Integer.MIN_VALUE, -1, 0, 3, 4, max + 1, PGStream.MAX_MESSAGE_LENGTH,
        Integer.MAX_VALUE};

    for (int length : rejected) {
      PGStream stream = streamOf(int4(length));
      IOException e = assertThrows(IOException.class,
          () -> stream.receiveMessageLength("ErrorResponse", 5, max), "length " + length + " should be rejected");
      assertTrue(e.getMessage().contains(String.valueOf(length)), e.getMessage());
    }
  }

  @Test
  void acceptsLengthsInsideTheRange() throws IOException {
    int max = PGStream.MAX_SMALL_MESSAGE_LENGTH;
    for (int length : new int[]{5, 6, 1024, max}) {
      assertEquals(length, streamOf(int4(length)).receiveMessageLength("ErrorResponse", 5, max));
    }
  }

  @Test
  void namesTheMessageInTheError() throws IOException {
    PGStream stream = streamOf(int4(3));
    IOException e = assertThrows(IOException.class,
        () -> stream.receiveMessageLength("ErrorResponse", 5, 100));
    assertTrue(e.getMessage().contains("ErrorResponse"), e.getMessage());
  }

  /** Subtracting the 4 length bytes from this value wraps to a positive two gigabyte size. */
  @Test
  void rejectsTheWraparoundCopyDataLength() throws IOException {
    PGStream stream = streamOf(int4(Integer.MIN_VALUE));
    assertThrows(IOException.class,
        () -> stream.receiveMessageLength("CopyData", 4, PGStream.MAX_MESSAGE_LENGTH));
  }

  /** libpq accepts a zero length CopyData body. */
  @Test
  void acceptsAZeroLengthCopyDataBody() throws IOException {
    assertEquals(4, streamOf(int4(4)).receiveMessageLength("CopyData", 4, PGStream.MAX_MESSAGE_LENGTH));
  }

  @Test
  void readsAValidDataRow() throws IOException, SQLException {
    // length, field count, then a 3 byte column, a null column and an empty column.
    byte[] message = new byte[]{0, 0, 0, 21, 0, 3, 0, 0, 0, 3, 'a', 'b', 'c',
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0};

    Tuple tuple = streamOf(message).receiveTupleV3();

    assertEquals(3, tuple.fieldCount());
    assertArrayEquals(new byte[]{'a', 'b', 'c'}, tuple.get(0));
    assertNull(tuple.get(1));
    assertArrayEquals(new byte[0], tuple.get(2));
  }

  @Test
  void rejectsAColumnLengthBelowNull() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 10, 0, 1, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFE};
    PGStream stream = streamOf(message);
    IOException e = assertThrows(IOException.class, () -> stream.receiveTupleV3());
    assertTrue(e.getMessage().contains("does not fit"), e.getMessage());
  }

  @Test
  void rejectsAColumnThatRunsPastTheMessage() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 10, 0, 1, 0, 16, 0, 0};
    PGStream stream = streamOf(message);
    IOException e = assertThrows(IOException.class, () -> stream.receiveTupleV3());
    // It must be the length check, because reading on would hit the end of the stream anyway.
    assertTrue(e.getMessage().contains("does not fit"), e.getMessage());
  }

  @Test
  void rejectsADataRowWhoseColumnsUnderrunItsEnvelope() throws IOException {
    // 4 length + 2 count + 4 column length leaves 11 of column data. The column accounts for 7.
    byte[] message = new byte[]{0, 0, 0, 21, 0, 1, 0, 0, 0, 7, 'a', 'b', 'c', 'd', 'e', 'f', 'g'};
    PGStream stream = streamOf(message);
    IOException e = assertThrows(IOException.class, () -> stream.receiveTupleV3());
    assertTrue(e.getMessage().contains("unread"), e.getMessage());
  }

  @Test
  void acceptsADataRowThatConsumesItsEnvelopeExactly() throws IOException, SQLException {
    byte[] message = new byte[]{0, 0, 0, 13, 0, 1, 0, 0, 0, 3, 'a', 'b', 'c'};

    Tuple tuple = streamOf(message).receiveTupleV3();

    assertEquals(1, tuple.fieldCount());
    assertArrayEquals(new byte[]{'a', 'b', 'c'}, tuple.get(0));
  }

  @Test
  void rejectsADataRowTooShortForItsColumnCount() throws IOException {
    // 100 columns in a message that cannot hold their lengths.
    byte[] message = new byte[]{0, 0, 0, 10, 0, 100, 0, 0, 0, 0};
    PGStream stream = streamOf(message);
    IOException e = assertThrows(IOException.class, () -> stream.receiveTupleV3());
    assertTrue(e.getMessage().contains("cannot hold"), e.getMessage());
  }

  @Test
  void marksTheStreamBrokenWhenALengthIsRefused() throws IOException {
    PGStream stream = streamOf(int4(Integer.MAX_VALUE));

    assertThrows(IOException.class,
        () -> stream.receiveMessageLength("ErrorResponse", 5, PGStream.MAX_SMALL_MESSAGE_LENGTH));

    assertTrue(stream.isBroken(), "the refusal must mark the stream broken");
    // A pool testing on borrow looks at this, so it decides whether the connection is handed out.
    assertTrue(stream.isClosed(), "a broken stream must report itself closed");
  }

  @Test
  void marksTheStreamBrokenWhenADataRowIsRefused() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 10, 0, 1, 0, 16, 0, 0};
    PGStream stream = streamOf(message);

    assertThrows(IOException.class, () -> stream.receiveTupleV3());

    assertTrue(stream.isBroken(), "the refusal must mark the stream broken");
  }

  @Test
  void leavesAnAcceptedLengthAlone() throws IOException {
    PGStream stream = streamOf(int4(PGStream.MAX_SMALL_MESSAGE_LENGTH));

    stream.receiveMessageLength("ErrorResponse", 5, PGStream.MAX_SMALL_MESSAGE_LENGTH);

    assertFalse(stream.isBroken());
    assertFalse(stream.isClosed());
  }

  @Test
  void rejectsAMessageWhoseReaderStoppedShort() throws IOException {
    // A ten byte message, of which the reader consumes two, then the next message type.
    byte[] message = new byte[]{0, 0, 0, 10, 1, 2, 3, 4, 5, 6, 'Z'};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("NoticeResponse", 5, 100);
    stream.receiveInteger2();

    IOException e = assertThrows(IOException.class, () -> stream.receiveMessageType());
    assertTrue(e.getMessage().contains("stopped at byte"), e.getMessage());
    assertTrue(stream.isBroken());
  }

  @Test
  void acceptsAMessageConsumedExactly() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 10, 1, 2, 3, 4, 5, 6, 'Z'};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("NoticeResponse", 5, 100);
    stream.skip(6);

    assertEquals('Z', stream.receiveMessageType());
  }

  /** A reader that runs past its message is as wrong as one that stops short. */
  @Test
  void rejectsAMessageWhoseReaderRanPast() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 6, 1, 2, 3, 4, 'Z'};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("NoticeResponse", 5, 100);
    stream.skip(4);

    assertThrows(IOException.class, () -> stream.receiveMessageType());
  }

  /** The first message of a connection has nothing before it to check against. */
  @Test
  void acceptsAMessageTypeWithNoMessageOutstanding() throws IOException {
    assertEquals('R', streamOf(new byte[]{'R'}).receiveMessageType());
  }

  @Test
  void rejectsAStringThatRunsPastItsMessage() throws IOException {
    // A nine byte message holding five bytes with no terminator among them.
    byte[] message = new byte[]{0, 0, 0, 9, 'a', 'b', 'c', 'd', 'e', 0};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("ParameterStatus", 6, 100);

    IOException e = assertThrows(IOException.class, () -> stream.receiveString());
    assertTrue(e.getMessage().contains("terminator"), e.getMessage());
    assertTrue(stream.isBroken());
  }

  /** The terminator is the last byte of the message, so the scan succeeds at its bound. */
  @Test
  void acceptsAStringThatEndsOnTheLastByteOfItsMessage() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 9, 'a', 'b', 'c', 'd', 0};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("ParameterStatus", 6, 100);

    assertEquals("abcd", stream.receiveString());
  }

  @Test
  void capsThePreAuthenticationMessageBelowTheBufferedOne() {
    assertTrue(PGStream.MAX_PRE_AUTH_MESSAGE_LENGTH < PGStream.MAX_BUFFERED_MESSAGE_LENGTH);
    // libpq's limit is on the declared length, which counts itself.
    assertEquals(30000, PGStream.MAX_PRE_AUTH_MESSAGE_LENGTH);
  }
}
