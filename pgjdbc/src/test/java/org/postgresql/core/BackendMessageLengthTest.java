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
 * The driver reads each backend message within the length that message declared. That length has
 * to fall inside the range the message type allows, and each DataRow column length has to fit the
 * bytes the message has left. A reader that stops short of the declared end, or runs past it, is
 * caught at the next message type.
 *
 * <p>Each case sits on one side or the other of one of those limits. The bytes come from a
 * {@link CannedSocketFactory} rather than from a server, so a test can give a length a value no
 * server would send.</p>
 */
@Isolated("Uses Locale.setDefault")
class BackendMessageLengthTest {

  private static Locale defaultLocale;

  /**
   * We force the root locale because the assertions below match the English text GT.tr returns,
   * and a translated default locale would fail them. The guard is partial: GT resolves its bundle
   * once, in a static initializer, so setting the locale here only reaches GT when this class is
   * the first to load it. What saves the assertions today is that no catalog carries a translation
   * of the messages they match.
   */
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

  /**
   * The limit under test is {@link PGStream#MAX_SMALL_MESSAGE_LENGTH}, so
   * {@link PGStream#MAX_MESSAGE_LENGTH} belongs among the refused lengths here, even though a
   * DataRow may declare it.
   */
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

  /**
   * A declared length of {@link Integer#MIN_VALUE} wraps round to a positive body size of two
   * gigabytes once the 4 length bytes are subtracted, so the length itself has to be refused.
   */
  @Test
  void rejectsTheWraparoundCopyDataLength() throws IOException {
    PGStream stream = streamOf(int4(Integer.MIN_VALUE));
    assertThrows(IOException.class,
        () -> stream.receiveMessageLength("CopyData", 4, PGStream.MAX_MESSAGE_LENGTH));
  }

  /**
   * The minimum for CopyData is 4, the length bytes alone, because libpq accepts a CopyData with
   * an empty body.
   */
  @Test
  void acceptsAZeroLengthCopyDataBody() throws IOException {
    assertEquals(4, streamOf(int4(4)).receiveMessageLength("CopyData", 4, PGStream.MAX_MESSAGE_LENGTH));
  }

  @Test
  void readsAValidDataRow() throws IOException, SQLException {
    // One column of each kind the protocol allows: 3 bytes of data, a SQL NULL at -1, and an
    // empty column at 0. The declared 21 bytes are 4 length + 2 count + (4 + 3) + 4 + 4.
    byte[] message = new byte[]{0, 0, 0, 21, 0, 3, 0, 0, 0, 3, 'a', 'b', 'c',
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0};

    Tuple tuple = streamOf(message).receiveTupleV3();

    assertEquals(3, tuple.fieldCount());
    assertArrayEquals(new byte[]{'a', 'b', 'c'}, tuple.get(0));
    assertNull(tuple.get(1));
    assertArrayEquals(new byte[0], tuple.get(2));
  }

  /** Only -1 is a null column, so the -2 in the last 4 bytes has to be refused. */
  @Test
  void rejectsAColumnLengthBelowNull() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 10, 0, 1, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFE};
    PGStream stream = streamOf(message);
    IOException e = assertThrows(IOException.class, () -> stream.receiveTupleV3());
    assertTrue(e.getMessage().contains("does not fit"), e.getMessage());
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
    assertTrue(e.getMessage().contains("does not fit"), e.getMessage());
  }

  @Test
  void rejectsADataRowWhoseColumnsUnderrunItsEnvelope() throws IOException {
    // A column that stops short satisfies every per-column check, so the only thing that catches
    // it is the unread-bytes check at the end. The declared 21 leave 11 for column data and the
    // one column declares 7, so 4 go unread.
    byte[] message = new byte[]{0, 0, 0, 21, 0, 1, 0, 0, 0, 7, 'a', 'b', 'c', 'd', 'e', 'f', 'g'};
    PGStream stream = streamOf(message);
    IOException e = assertThrows(IOException.class, () -> stream.receiveTupleV3());
    assertTrue(e.getMessage().contains("unread"), e.getMessage());
  }

  /**
   * Sends a DataRow whose one column fills the message exactly, and expects it to be read. The
   * declared 13 bytes leave 3 for column data, and the column declares 3.
   */
  @Test
  void acceptsADataRowThatConsumesItsEnvelopeExactly() throws IOException, SQLException {
    byte[] message = new byte[]{0, 0, 0, 13, 0, 1, 0, 0, 0, 3, 'a', 'b', 'c'};

    Tuple tuple = streamOf(message).receiveTupleV3();

    assertEquals(1, tuple.fieldCount());
    assertArrayEquals(new byte[]{'a', 'b', 'c'}, tuple.get(0));
  }

  @Test
  void rejectsADataRowTooShortForItsColumnCount() throws IOException {
    // Without the count check the driver would read 100 column lengths out of the messages behind
    // this one. Those lengths need 400 bytes and the declared 10 leave 4.
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
    // PgConnection.isClosed() reaches PGStream.isClosed() through the executor's close action,
    // so a pool that tests a connection before handing it out sees this one as closed.
    assertTrue(stream.isClosed(), "a broken stream must report itself closed");
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
    assertTrue(e.getMessage().contains("protocol violation"), e.getMessage());
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
    // A short read leaves body bytes that the driver would read as the next message type and
    // length, so the position check is the only thing that catches it. The declared 10 bytes leave
    // 6 for the body, the reader consumes 2, and the 'Z' after them is the next message type.
    byte[] message = new byte[]{0, 0, 0, 10, 1, 2, 3, 4, 5, 6, 'Z'};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("NoticeResponse", 5, 100);
    stream.receiveInteger2();

    IOException e = assertThrows(IOException.class, () -> stream.receiveMessageType());
    assertTrue(e.getMessage().contains("stopped at byte"), e.getMessage());
    assertTrue(stream.isBroken());
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

    assertEquals('Z', stream.receiveMessageType());
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

    assertThrows(IOException.class, () -> stream.receiveMessageType());
  }

  /** There is no previous message to check before the first one on a connection. */
  @Test
  void acceptsAMessageTypeWithNoMessageOutstanding() throws IOException {
    assertEquals('R', streamOf(new byte[]{'R'}).receiveMessageType());
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
    assertTrue(e.getMessage().contains("terminator"), e.getMessage());
    assertTrue(stream.isBroken());
  }

  /** The terminator is the last byte of the message, so the scan succeeds at its limit. */
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
    // libpq's MAX_ERRLEN bounds the declared length, which counts its own 4 bytes, so the driver
    // uses the same 30000 without adjusting it.
    assertEquals(30000, PGStream.MAX_PRE_AUTH_MESSAGE_LENGTH);
  }
}
