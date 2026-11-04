/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Reads backend messages straight from a {@link CannedSocketFactory}, so a test can declare a
 * length no server would send. Three checks are covered: the declared length has to fall in the
 * range the message type allows, each DataRow column length has to fit the bytes the message has
 * left, and a reader that stops short of the declared end or runs past it is caught at the next
 * message type.
 *
 * <p>Each assertion builds its expected text with {@link GT#tr}, the call the driver used, so the
 * tests hold in any locale.</p>
 */
class BackendMessageLengthTest {

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

  /** The refusal {@link PGStream#receiveMessageLength} builds for a length outside its range. */
  private static String lengthRefusal(String messageName, int declared, int min, int max) {
    return GT.tr("Backend declared a {0} message length of {1} bytes, expected {2} to {3} bytes.",
        messageName, String.valueOf(declared), String.valueOf(min), String.valueOf(max));
  }

  /** The refusal {@link PGStream#receiveTupleV3} builds for a column that does not fit. */
  private static String columnRefusal(int declared, int remaining) {
    return GT.tr("DataRow column of {0} bytes does not fit in the {1} bytes left of the message.",
        String.valueOf(declared), String.valueOf(remaining));
  }

  @ParameterizedTest(name = "a declared length of {0}")
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 3, 4, PGStream.MAX_SMALL_MESSAGE_LENGTH + 1,
      PGStream.MAX_MESSAGE_LENGTH, Integer.MAX_VALUE})
  void rejectsLengthsOutsideTheRange(int declared) throws IOException {
    int max = PGStream.MAX_SMALL_MESSAGE_LENGTH;
    PGStream stream = streamOf(int4(declared));

    IOException e = assertThrows(IOException.class,
        () -> stream.receiveMessageLength("ErrorResponse", 5, max));

    assertEquals(lengthRefusal("ErrorResponse", declared, 5, max), e.getMessage());
  }

  /**
   * The limit under test is {@link PGStream#MAX_SMALL_MESSAGE_LENGTH}, so
   * {@link PGStream#MAX_MESSAGE_LENGTH} is among the lengths refused above, even though a DataRow
   * may declare it. Here 5 is the minimum passed in, and 10000 the maximum.
   */
  @ParameterizedTest(name = "a declared length of {0}")
  @ValueSource(ints = {5, 6, 1024, PGStream.MAX_SMALL_MESSAGE_LENGTH})
  void acceptsLengthsInsideTheRange(int declared) throws IOException {
    int max = PGStream.MAX_SMALL_MESSAGE_LENGTH;

    assertEquals(declared,
        streamOf(int4(declared)).receiveMessageLength("ErrorResponse", 5, max),
        "receiveMessageLength must return the length it read");
  }

  /** The name the caller passes is the name the reader of the error sees. */
  @Test
  void namesTheMessageInTheError() throws IOException {
    PGStream stream = streamOf(int4(3));

    IOException e = assertThrows(IOException.class,
        () -> stream.receiveMessageLength("ErrorResponse", 5, 100));

    assertEquals(lengthRefusal("ErrorResponse", 3, 5, 100), e.getMessage());
  }

  /**
   * A declared length of {@link Integer#MIN_VALUE} wraps round to a positive body size of two
   * gigabytes once the 4 length bytes are subtracted, so the length itself has to be refused.
   */
  @Test
  void rejectsTheWraparoundCopyDataLength() throws IOException {
    PGStream stream = streamOf(int4(Integer.MIN_VALUE));

    IOException e = assertThrows(IOException.class,
        () -> stream.receiveMessageLength("CopyData", 4, PGStream.MAX_MESSAGE_LENGTH));

    assertEquals(lengthRefusal("CopyData", Integer.MIN_VALUE, 4, PGStream.MAX_MESSAGE_LENGTH),
        e.getMessage());
  }

  /**
   * The minimum for CopyData is 4, the length bytes alone, because libpq accepts a CopyData with
   * an empty body.
   */
  @Test
  void acceptsAZeroLengthCopyDataBody() throws IOException {
    assertEquals(4,
        streamOf(int4(4)).receiveMessageLength("CopyData", 4, PGStream.MAX_MESSAGE_LENGTH),
        "a CopyData of 4 bytes must be accepted");
  }

  @Test
  void readsAValidDataRow() throws IOException, SQLException {
    // One column of each kind the protocol allows: 3 bytes of data, a SQL NULL at -1, and an
    // empty column at 0. The declared 21 bytes are 4 length + 2 count + (4 + 3) + 4 + 4.
    byte[] message = new byte[]{0, 0, 0, 21, 0, 3, 0, 0, 0, 3, 'a', 'b', 'c',
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0};

    Tuple tuple = streamOf(message).receiveTupleV3();

    assertAll(
        () -> assertEquals(3, tuple.fieldCount(), "column count"),
        () -> assertArrayEquals(new byte[]{'a', 'b', 'c'}, tuple.get(0), "first column"),
        () -> assertNull(tuple.get(1), "a column of -1 bytes must read as SQL NULL"),
        () -> assertArrayEquals(new byte[0], tuple.get(2), "a column of 0 bytes must read empty"));
  }

  /** Only -1 is a null column, so the -2 in the last 4 bytes has to be refused. */
  @Test
  void rejectsAColumnLengthBelowNull() throws IOException {
    byte[] message =
        new byte[]{0, 0, 0, 10, 0, 1, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFE};
    PGStream stream = streamOf(message);

    IOException e = assertThrows(IOException.class, () -> stream.receiveTupleV3());

    // The declared 10 bytes are the length, the count and the column length, so the message has
    // no room for column data at all.
    assertEquals(columnRefusal(-2, 0), e.getMessage());
  }

  @Test
  void rejectsAColumnThatRunsPastTheMessage() throws IOException {
    // Those four column length bytes are an int4, so the column claims 1048576 bytes and not the
    // 16 they look like, and the declared 10 leave no room for column data at all.
    byte[] message = new byte[]{0, 0, 0, 10, 0, 1, 0, 16, 0, 0};
    PGStream stream = streamOf(message);

    IOException e = assertThrows(IOException.class, () -> stream.receiveTupleV3());

    // We assert on the length check rather than on the failure alone, because a reader that ran
    // on would hit the end of the stream and fail anyway, and then this test would pass for the
    // wrong reason.
    assertEquals(columnRefusal(1048576, 0), e.getMessage());
  }

  @Test
  void rejectsADataRowWhoseColumnsUnderrunItsEnvelope() throws IOException {
    // A column that stops short satisfies every per-column check, so the only thing that catches
    // it is the unread-bytes check at the end. The declared 21 leave 11 for column data and the
    // one column declares 7, so 4 go unread.
    byte[] message = new byte[]{0, 0, 0, 21, 0, 1, 0, 0, 0, 7, 'a', 'b', 'c', 'd', 'e', 'f', 'g'};
    PGStream stream = streamOf(message);

    IOException e = assertThrows(IOException.class, () -> stream.receiveTupleV3());

    assertEquals(GT.tr("DataRow of {0} bytes has {1} unread bytes.", "21", "4"), e.getMessage());
  }

  /**
   * Sends a DataRow whose one column fills the message exactly, and expects it to be read. The
   * declared 13 bytes leave 3 for column data, and the column declares 3.
   */
  @Test
  void acceptsADataRowThatConsumesItsEnvelopeExactly() throws IOException, SQLException {
    byte[] message = new byte[]{0, 0, 0, 13, 0, 1, 0, 0, 0, 3, 'a', 'b', 'c'};

    Tuple tuple = streamOf(message).receiveTupleV3();

    assertAll(
        () -> assertEquals(1, tuple.fieldCount(), "column count"),
        () -> assertArrayEquals(new byte[]{'a', 'b', 'c'}, tuple.get(0), "the one column"));
  }

  @Test
  void rejectsADataRowTooShortForItsColumnCount() throws IOException {
    // Without the count check the driver would read 100 column lengths out of the messages behind
    // this one. Those lengths need 400 bytes and the declared 10 leave 4.
    byte[] message = new byte[]{0, 0, 0, 10, 0, 100, 0, 0, 0, 0};
    PGStream stream = streamOf(message);

    IOException e = assertThrows(IOException.class, () -> stream.receiveTupleV3());

    assertEquals(GT.tr("DataRow of {0} bytes cannot hold {1} column lengths.", "10", "100"),
        e.getMessage());
  }

  @Test
  void marksTheStreamBrokenWhenALengthIsRefused() throws IOException {
    PGStream stream = streamOf(int4(Integer.MAX_VALUE));

    assertThrows(IOException.class,
        () -> stream.receiveMessageLength("ErrorResponse", 5, PGStream.MAX_SMALL_MESSAGE_LENGTH));

    assertAll(
        () -> assertTrue(stream.isBroken(), "the refusal must mark the stream broken"),
        // PgConnection.isClosed() reaches PGStream.isClosed() through the executor's close action,
        // so a pool that tests a connection before handing it out sees this one as closed.
        () -> assertTrue(stream.isClosed(), "a broken stream must report itself closed"));
  }

  @Test
  void marksTheStreamBrokenWhenADataRowIsRefused() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 10, 0, 1, 0, 16, 0, 0};
    PGStream stream = streamOf(message);

    assertThrows(IOException.class, () -> stream.receiveTupleV3());

    assertTrue(stream.isBroken(), "the refusal must mark the stream broken");
  }

  /** A read after a refusal reports the refusal, not whatever fails next. */
  @Test
  void refusesToReadPastABrokenStream() throws IOException {
    // The declared length of 3 is below the 5 byte minimum, and the 'Z' behind it is a message
    // type the driver would otherwise read.
    PGStream stream = streamOf(new byte[]{0, 0, 0, 3, 'Z'});

    assertThrows(IOException.class,
        () -> stream.receiveMessageLength("ErrorResponse", 5, PGStream.MAX_SMALL_MESSAGE_LENGTH));

    IOException e = assertThrows(IOException.class, () -> stream.receiveMessageType());

    assertEquals(GT.tr("The connection was dropped after a protocol violation."), e.getMessage());
  }

  @Test
  void leavesAnAcceptedLengthAlone() throws IOException {
    PGStream stream = streamOf(int4(PGStream.MAX_SMALL_MESSAGE_LENGTH));

    stream.receiveMessageLength("ErrorResponse", 5, PGStream.MAX_SMALL_MESSAGE_LENGTH);

    assertAll(
        () -> assertFalse(stream.isBroken(), "an accepted length must leave the stream usable"),
        () -> assertFalse(stream.isClosed(), "an accepted length must leave the stream open"));
  }

  @Test
  void rejectsAMessageWhoseReaderStoppedShort() throws IOException {
    // A short read leaves body bytes that the driver would read as the next message type and
    // length, so the position check is the only thing that catches it. The declared 10 bytes leave
    // 6 for the body, the reader consumes 2, and the 'Z' after them is the next message type.
    byte[] message = new byte[]{0, 0, 0, 10, 1, 2, 3, 4, 5, 6, 'Z'};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("NoticeResponse", 5, 100);
    stream.receiveInteger2();

    IOException e = assertThrows(IOException.class, () -> stream.receiveMessageType());

    assertAll(
        // The 4 length bytes end at stream position 4, so the declared end is 4 + 6, and the two
        // bytes the reader took leave it at 6.
        () -> assertEquals(GT.tr("The previous backend message declared its end at byte {0} of the"
            + " stream, but its reader stopped at byte {1}.", "10", "6"), e.getMessage()),
        () -> assertTrue(stream.isBroken(), "the refusal must mark the stream broken"));
  }

  /**
   * Skips exactly the 6 body bytes the declared 10 leave, and expects the next message type to
   * read normally.
   */
  @Test
  void acceptsAMessageConsumedExactly() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 10, 1, 2, 3, 4, 5, 6, 'Z'};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("NoticeResponse", 5, 100);
    stream.skip(6);

    assertEquals('Z', stream.receiveMessageType(), "the next message type must be readable");
  }

  /**
   * Reading past the end of a message is refused the same way as stopping short of it. The
   * declared 6 bytes leave 2 for the body, and the reader skips 4.
   */
  @Test
  void rejectsAMessageWhoseReaderRanPast() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 6, 1, 2, 3, 4, 'Z'};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("NoticeResponse", 5, 100);
    stream.skip(4);

    IOException e = assertThrows(IOException.class, () -> stream.receiveMessageType());

    assertEquals(GT.tr("The previous backend message declared its end at byte {0} of the stream,"
        + " but its reader stopped at byte {1}.", "6", "8"), e.getMessage());
  }

  /** There is no previous message to check before the first one on a connection. */
  @Test
  void acceptsAMessageTypeWithNoMessageOutstanding() throws IOException {
    assertEquals('R', streamOf(new byte[]{'R'}).receiveMessageType(),
        "the first message type on a connection must be readable");
  }

  @Test
  void rejectsAStringThatRunsPastItsMessage() throws IOException {
    // A scan that the message does not bound would take the terminator belonging to whatever
    // follows and leave the stream out of sync. The declared 9 bytes leave 5 for the body with no
    // terminator among them, and the terminator is the next byte.
    byte[] message = new byte[]{0, 0, 0, 9, 'a', 'b', 'c', 'd', 'e', 0};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("ParameterStatus", 6, 100);

    IOException e = assertThrows(IOException.class, () -> stream.receiveString());

    assertAll(
        // The scan is given the 5 bytes the message has left, and stops there.
        () -> assertEquals(GT.tr("No string terminator within {0} bytes.", "5"), e.getMessage()),
        () -> assertTrue(stream.isBroken(), "the refusal must mark the stream broken"));
  }

  /** The terminator is the last byte of the message, so the scan succeeds at its limit. */
  @Test
  void acceptsAStringThatEndsOnTheLastByteOfItsMessage() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 9, 'a', 'b', 'c', 'd', 0};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("ParameterStatus", 6, 100);

    assertEquals("abcd", stream.receiveString(), "the string must be read in full");
  }

  @Test
  void capsThePreAuthenticationMessageBelowTheBufferedOne() {
    assertAll(
        () -> assertTrue(
            PGStream.MAX_PRE_AUTH_MESSAGE_LENGTH < PGStream.MAX_BUFFERED_MESSAGE_LENGTH,
            "the pre-authentication limit must be below the buffered one"),
        // libpq's MAX_ERRLEN bounds the declared length, which counts its own 4 bytes, so the
        // driver uses the same 30000 without adjusting it.
        () -> assertEquals(30000, PGStream.MAX_PRE_AUTH_MESSAGE_LENGTH,
            "the pre-authentication limit must be libpq's MAX_ERRLEN"));
  }
}
