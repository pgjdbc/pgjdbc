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
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.postgresql.core.PGStreamTestSupport.assertBroken;
import static org.postgresql.core.PGStreamTestSupport.openStream;

import org.postgresql.test.util.FakeSocket;
import org.postgresql.test.util.FakeSocketFactory;
import org.postgresql.test.util.Wire;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * {@link PGStream#receiveTupleV3()} checks every length in a DataRow against the bytes the
 * message has left before the length sizes an allocation or a read, in every
 * {@link ProtocolHardeningMode}. A DataRow that fails a check breaks the stream, and so does a row
 * that exceeds {@code maxResultBuffer}.
 *
 * <p>The DataRow body is a 2-byte unsigned field count, then per field a 4-byte length
 * ({@code -1} for NULL) followed by that many bytes. The declared message length counts its own
 * 4 bytes. Each row here is followed by the byte {@code 'Z'} where the test reads on after
 * it.</p>
 */
class PGStreamDataRowTest {

  @Test
  void aRowWithValueNullAndEmptyFieldsIsReturned() throws Exception {
    byte[] row = new Wire().int4(20).int2(3)
        .int4(2).int1('a').int1('b')
        .int4(-1)
        .int4(0)
        .int1('Z').toBytes();
    PGStream stream = openStream(new FakeSocket(row));

    Tuple tuple = stream.receiveTupleV3();

    assertAll(
        () -> assertEquals(3, tuple.fieldCount(), "fieldCount()"),
        () -> assertArrayEquals("ab".getBytes(StandardCharsets.US_ASCII), tuple.get(0), "field 0"),
        () -> assertNull(tuple.get(1), "field 1"),
        () -> assertArrayEquals(new byte[0], tuple.get(2), "field 2"),
        () -> assertEquals('Z', stream.receiveChar(), "byte after the row"),
        () -> assertFalse(stream.isClosed(), "isClosed()"));
  }

  @Test
  void aRowWithNoFieldsIsReturned() throws Exception {
    PGStream stream = openStream(new FakeSocket(new Wire().int4(6).int2(0).int1('Z').toBytes()));

    Tuple tuple = stream.receiveTupleV3();

    assertAll(
        () -> assertEquals(0, tuple.fieldCount(), "fieldCount()"),
        () -> assertEquals('Z', stream.receiveChar(), "byte after the row"));
  }

  /**
   * The field count is unsigned, so {@code 0xffff} is 65535 fields. 65535 NULL fields take
   * 6 + 4 * 65535 = 262146 bytes.
   */
  @Test
  void aFieldCountOf0xffffIs65535Fields() throws Exception {
    byte[] nullLengths = new byte[4 * 65535];
    Arrays.fill(nullLengths, (byte) 0xff);
    PGStream stream = openStream(new FakeSocket(
        new Wire().int4(262146).int2(0xffff).raw(nullLengths).int1('Z').toBytes()));

    Tuple tuple = stream.receiveTupleV3();

    assertAll(
        () -> assertEquals(65535, tuple.fieldCount(), "fieldCount()"),
        () -> assertNull(tuple.get(65534), "field 65534"),
        () -> assertEquals('Z', stream.receiveChar(), "byte after the row"));
  }

  @Test
  void aMessageLengthBelowTheFieldCountIsRejected() {
    FakeSocket socket = new FakeSocket(new Wire().int4(5).int2(0).toBytes());
    PGStream stream = openStream(socket);

    IOException e = assertThrowsExactly(IOException.class, stream::receiveTupleV3);

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has invalid length {1} (expected between {2} and {3}).",
                "DataRow", "5", "6", "1073741823"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  @Test
  void aFieldCountWhoseLengthPrefixesExactlyFillTheRowIsAccepted() throws Exception {
    PGStream stream = openStream(new FakeSocket(new Wire().int4(10).int2(1).int4(-1).toBytes()));

    Tuple tuple = stream.receiveTupleV3();

    assertAll(
        () -> assertEquals(1, tuple.fieldCount(), "fieldCount()"),
        () -> assertNull(tuple.get(0), "field 0"));
  }

  /**
   * Two length prefixes need 8 bytes, and a 13-byte DataRow has 7 after its field count.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aFieldCountWhoseLengthPrefixesDoNotFitIsRejected(ProtocolHardeningMode mode) {
    FakeSocket socket = new FakeSocket(
        new Wire().int4(13).int2(2).int4(-1).bytes(3).toBytes());
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);

    IOException e = assertThrowsExactly(IOException.class, stream::receiveTupleV3);

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. DataRow field count {0} requires at least {1} bytes for per-field length prefixes, but the message size is only {2}.",
                "2", "8", "13"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aFieldLengthBelowMinusOneIsRejected(ProtocolHardeningMode mode) {
    FakeSocket socket = new FakeSocket(new Wire().int4(10).int2(1).int4(-2).toBytes());
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);

    IOException e = assertThrowsExactly(IOException.class, stream::receiveTupleV3);

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. DataRow field {0} has negative length {1}.", "0", "-2"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  @Test
  void aFieldAsLongAsTheBytesLeftInTheRowIsAccepted() throws Exception {
    PGStream stream = openStream(
        new FakeSocket(new Wire().int4(14).int2(1).int4(4).bytes(4).int1('Z').toBytes()));

    Tuple tuple = stream.receiveTupleV3();

    assertAll(
        () -> assertArrayEquals("abcd".getBytes(StandardCharsets.US_ASCII), tuple.get(0),
            "field 0"),
        () -> assertEquals('Z', stream.receiveChar(), "byte after the row"));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aFieldOneByteLongerThanTheRowIsRejected(ProtocolHardeningMode mode) {
    FakeSocket socket = new FakeSocket(
        new Wire().int4(14).int2(1).int4(5).bytes(5).toBytes());
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);

    IOException e = assertThrowsExactly(IOException.class, stream::receiveTupleV3);

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. DataRow field {0} length {1} exceeds remaining row bytes {2}.",
                "0", "5", "4"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  /**
   * A 20-byte DataRow with two fields has 6 data bytes; the first field takes 3, so the second
   * may take at most 3.
   */
  @Test
  void aLaterFieldIsCheckedAgainstTheBytesEarlierFieldsLeft() {
    FakeSocket socket = new FakeSocket(
        new Wire().int4(20).int2(2).int4(3).bytes(3).int4(4).bytes(4).toBytes());
    PGStream stream = openStream(socket);

    IOException e = assertThrowsExactly(IOException.class, stream::receiveTupleV3);

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. DataRow field {0} length {1} exceeds remaining row bytes {2}.",
                "1", "4", "3"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  /**
   * A field length read from inside row data is rejected before it sizes an allocation. Issue
   * #4015 reported {@code new byte[1697905436]} from such a length, followed by a socket read that
   * never returned.
   */
  @Test
  void aFieldLengthFarBeyondTheRowIsRejectedBeforeAllocating() {
    FakeSocket socket = new FakeSocket(
        new Wire().int4(14).int2(1).int4(1697905436).bytes(4).toBytes());
    PGStream stream = openStream(socket);

    IOException e = assertThrowsExactly(IOException.class, stream::receiveTupleV3);

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. DataRow field {0} length {1} exceeds remaining row bytes {2}.",
                "0", "1697905436", "4"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  @Test
  void fieldsThatEndBeforeTheDeclaredLengthAreRejected() {
    FakeSocket socket = new FakeSocket(
        new Wire().int4(16).int2(1).int4(4).bytes(4).bytes(2).toBytes());
    PGStream stream = openStream(socket);

    IOException e = assertThrowsExactly(IOException.class, stream::receiveTupleV3);

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has {1} unread bytes.", "DataRow", "2"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  /**
   * An {@link OutOfMemoryError} while a field body is read leaves an unknown number of its bytes
   * consumed, so the stream cannot return to a message boundary and is broken.
   */
  @Test
  void anOutOfMemoryErrorWhileReadingAFieldBreaksTheStream() throws Exception {
    FakeSocket socket = new FakeSocket(new Wire().int4(14).int2(1).int4(4).bytes(4).toBytes());
    PGStream stream = new PGStream(new FakeSocketFactory(socket),
        new HostSpec("localhost", 5432), 0, 8192) {
      @Override
      public void receive(byte[] buf, int off, int siz) {
        throw new OutOfMemoryError("thrown by the test while a field is read");
      }
    };

    assertThrowsExactly(OutOfMemoryError.class, stream::receiveTupleV3);

    assertBroken(stream, socket);
  }

  @Test
  void aRowAtMaxResultBufferIsReturned() throws Exception {
    PGStream stream = openStream(
        new FakeSocket(new Wire().int4(20).int2(1).int4(10).bytes(10).toBytes()));
    stream.setMaxResultBuffer("10");

    Tuple tuple = stream.receiveTupleV3();

    assertAll(
        () -> assertEquals(1, tuple.fieldCount(), "fieldCount()"),
        () -> assertFalse(stream.isClosed(), "isClosed()"));
  }

  /**
   * The row is left unread when the limit is exceeded, so the stream must not be usable
   * afterwards. The overflow used to throw with the stream still open, and the next message type
   * was then read from inside the row.
   */
  @Test
  void aRowOverMaxResultBufferBreaksTheStream() throws Exception {
    FakeSocket socket = new FakeSocket(
        new Wire().int4(21).int2(1).int4(11).bytes(11).toBytes());
    PGStream stream = openStream(socket);
    stream.setMaxResultBuffer("10");

    PSQLException e = assertThrowsExactly(PSQLException.class, stream::receiveTupleV3);

    assertAll(
        () -> assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), e.getSQLState(), "SQLState"),
        () -> assertBroken(stream, socket));
  }
}
