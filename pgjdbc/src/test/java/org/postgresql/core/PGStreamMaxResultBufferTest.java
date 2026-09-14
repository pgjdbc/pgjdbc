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
import org.postgresql.test.util.Wire;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Arrays;

/**
 * {@link PGStream#receiveTupleV3()} skips a DataRow that does not fit within
 * {@code maxResultBuffer}, on its own or added to the rows counted before it, and leaves the
 * stream open on the next message.
 *
 * <p>The first skipped row throws with SQLState 08S01, and each later one returns {@code null}
 * until {@link PGStream#clearOversizedRowReport()} is called. A row whose unread body is over
 * 64 MB (64000000 bytes) breaks the stream instead. {@code maxResultBuffer} is a limit the user
 * set, so the tests that skip or break run in every {@link ProtocolHardeningMode}.</p>
 *
 * <p>The limit counts the data bytes of a row, not its length prefixes. The rows here are
 * followed by the byte {@code 'Z'}, which a test reads to show the stream is on a message
 * boundary.</p>
 */
class PGStreamMaxResultBufferTest {
  // Message ids copied verbatim from PGStream, so each assertion pins which error was thrown while
  // surviving a translation refresh.
  private static final String SINGLE_ROW =
      "Result set exceeded maxResultBuffer limit. A single row of {0} bytes exceeds the limit of {1} bytes, so the row was skipped.";
  private static final String BUFFERED_ROWS =
      "Result set exceeded maxResultBuffer limit. The rows buffered since the last ReadyForQuery hold {0} bytes, so a row of {1} bytes does not fit within the limit of {2} bytes and was skipped.";
  private static final String UNSKIPPABLE =
      "Result set exceeded maxResultBuffer limit. A row of {0} bytes does not fit within the limit of {1} bytes, and its {2} unread bytes cannot be skipped, so the connection is closed.";

  @Test
  void aRowAtTheLimitIsReturned() throws Exception {
    PGStream stream = openStream(new FakeSocket(rows(row(10))));
    stream.setMaxResultBuffer("10");

    Tuple tuple = stream.receiveTupleV3();

    assertArrayEquals(new Wire().bytes(10).toBytes(), assertNotNull(tuple).get(0), "field 0");
  }

  /**
   * The row has two fields of 5 and 6 bytes, so its 11 data bytes are one over the limit, and its
   * unread body is 19 bytes including the two length prefixes.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aRowOverTheLimitIsSkippedAndTheStreamStaysOnTheNextMessage(ProtocolHardeningMode mode)
      throws Exception {
    FakeSocket socket = new FakeSocket(rows(row(5, 6)));
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);
    stream.setMaxResultBuffer("10");

    PSQLException e = assertThrowsExactly(PSQLException.class, stream::receiveTupleV3);
    int next = stream.receiveChar();

    assertAll(
        () -> assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(GT.tr(SINGLE_ROW, "11", "10"), e.getMessage()),
        () -> assertEquals('Z', next, "the byte after the row"),
        () -> assertFalse(stream.isClosed(), "isClosed()"),
        () -> assertFalse(socket.closed, "socket closed"));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aRowThatFitsAloneButNotWithTheRowsBeforeItIsSkipped(ProtocolHardeningMode mode)
      throws Exception {
    FakeSocket socket = new FakeSocket(rows(row(8), row(13)));
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);
    stream.setMaxResultBuffer("20");
    stream.receiveTupleV3();

    PSQLException e = assertThrowsExactly(PSQLException.class, stream::receiveTupleV3);
    int next = stream.receiveChar();

    assertAll(
        () -> assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(GT.tr(BUFFERED_ROWS, "8", "13", "20"), e.getMessage()),
        () -> assertEquals('Z', next, "the byte after the row"),
        () -> assertFalse(stream.isClosed(), "isClosed()"));
  }

  /**
   * A row of exactly the limit fits on its own, so after 5 buffered bytes it is reported as not
   * fitting with the buffered rows, not as a single row over the limit.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aRowAtTheLimitAfterEarlierRowsIsReportedAgainstTheBufferedRows(ProtocolHardeningMode mode)
      throws Exception {
    PGStream stream = openStream(new FakeSocket(rows(row(5), row(10))));
    stream.setProtocolHardeningMode(mode);
    stream.setMaxResultBuffer("10");
    stream.receiveTupleV3();

    PSQLException e = assertThrowsExactly(PSQLException.class, stream::receiveTupleV3);

    assertEquals(GT.tr(BUFFERED_ROWS, "5", "10", "10"), e.getMessage());
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aSecondSkippedRowReturnsNullAndLeavesTheStreamOnTheNextMessage(ProtocolHardeningMode mode)
      throws Exception {
    FakeSocket socket = new FakeSocket(rows(row(11), row(12)));
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);
    stream.setMaxResultBuffer("10");
    assertThrowsExactly(PSQLException.class, stream::receiveTupleV3, "first skipped row");

    Tuple second = stream.receiveTupleV3();
    int next = stream.receiveChar();

    assertAll(
        () -> assertNull(second, "second skipped row"),
        () -> assertEquals('Z', next, "the byte after the second row"),
        () -> assertFalse(stream.isClosed(), "isClosed()"));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void clearOversizedRowReportMakesTheNextSkippedRowThrowAgain(ProtocolHardeningMode mode)
      throws Exception {
    PGStream stream = openStream(new FakeSocket(rows(row(11), row(12))));
    stream.setProtocolHardeningMode(mode);
    stream.setMaxResultBuffer("10");
    assertThrowsExactly(PSQLException.class, stream::receiveTupleV3, "first skipped row");

    stream.clearOversizedRowReport();
    PSQLException e = assertThrowsExactly(PSQLException.class, stream::receiveTupleV3,
        "skipped row after clearOversizedRowReport()");

    assertEquals(GT.tr(SINGLE_ROW, "12", "10"), e.getMessage());
  }

  /**
   * After an 8-byte row and a skipped 13-byte row, a 12-byte row reaches the limit of 20 exactly,
   * which it would exceed if the skipped row had been counted.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aSkippedRowAddsNothingToTheBufferedCount(ProtocolHardeningMode mode) throws Exception {
    PGStream stream = openStream(new FakeSocket(rows(row(8), row(13), row(12))));
    stream.setProtocolHardeningMode(mode);
    stream.setMaxResultBuffer("20");
    stream.receiveTupleV3();
    assertThrowsExactly(PSQLException.class, stream::receiveTupleV3, "skipped 13-byte row");

    Tuple third = stream.receiveTupleV3();

    assertEquals(12, assertNotNull(third, "third row").get(0).length, "third row field 0 length");
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aSkippedRowLeavesTheMaxRowSizeUnchanged(ProtocolHardeningMode mode) throws Exception {
    PGStream stream = openStream(new FakeSocket(rows(row(4), row(11))));
    stream.setProtocolHardeningMode(mode);
    stream.setMaxResultBuffer("10");
    stream.receiveTupleV3();

    assertThrowsExactly(PSQLException.class, stream::receiveTupleV3, "skipped 11-byte row");

    assertEquals(4, stream.getMaxRowSizeBytes(), "getMaxRowSizeBytes()");
  }

  /**
   * One field of 63999996 bytes after its 4-byte length prefix leaves an unread body of exactly
   * 64000000 bytes. The body is generated as it is read, so the test allocates none of it.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void anUnreadBodyOf64000000BytesIsSkipped(ProtocolHardeningMode mode) throws Exception {
    int fieldLength = 63999996;
    FakeSocket socket = new FakeSocket(new SequenceInputStream(
        new SequenceInputStream(new ByteArrayInputStream(rowHeader(fieldLength)),
            new Zeros(fieldLength)),
        new ByteArrayInputStream(new byte[]{'Z'})));
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);
    stream.setMaxResultBuffer("10");

    PSQLException e = assertThrowsExactly(PSQLException.class, stream::receiveTupleV3);
    int next = stream.receiveChar();

    assertAll(
        () -> assertEquals(GT.tr(SINGLE_ROW, "63999996", "10"), e.getMessage()),
        () -> assertEquals('Z', next, "the byte after the row"),
        () -> assertFalse(stream.isClosed(), "isClosed()"));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void anUnreadBodyOf64000001BytesBreaksTheStream(ProtocolHardeningMode mode) throws Exception {
    FakeSocket socket = new FakeSocket(rowHeader(63999997));
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);
    stream.setMaxResultBuffer("10");

    PSQLException e = assertThrowsExactly(PSQLException.class, stream::receiveTupleV3);

    assertAll(
        () -> assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(GT.tr(UNSKIPPABLE, "63999997", "10", "64000001"), e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  /** A DataRow without its type byte, with one non-NULL field per entry of {@code fieldLengths}. */
  private static byte[] row(int... fieldLengths) {
    Wire wire = new Wire()
        .int4(4 + 2 + 4 * fieldLengths.length + Arrays.stream(fieldLengths).sum())
        .int2(fieldLengths.length);
    for (int length : fieldLengths) {
      wire.int4(length).bytes(length);
    }
    return wire.toBytes();
  }

  /** The given rows followed by {@code 'Z'}. */
  private static byte[] rows(byte[]... rows) {
    Wire wire = new Wire();
    for (byte[] row : rows) {
      wire.raw(row);
    }
    return wire.int1('Z').toBytes();
  }

  /** The length, field count, and field length of a one-field DataRow, with no field body. */
  private static byte[] rowHeader(int fieldLength) {
    return new Wire().int4(4 + 2 + 4 + fieldLength).int2(1).int4(fieldLength).toBytes();
  }

  private static <T> T assertNotNull(T value) {
    return assertNotNull(value, "tuple");
  }

  private static <T> T assertNotNull(T value, String what) {
    org.junit.jupiter.api.Assertions.assertNotNull(value, what);
    return value;
  }

  /** Serves {@code count} zero bytes. */
  private static final class Zeros extends InputStream {
    private long remaining;

    Zeros(long count) {
      remaining = count;
    }

    @Override
    public int read() {
      if (remaining == 0) {
        return -1;
      }
      remaining--;
      return 0;
    }

    @Override
    public int read(byte[] b, int off, int len) {
      if (remaining == 0) {
        return -1;
      }
      int n = (int) Math.min(len, remaining);
      Arrays.fill(b, off, off + n, (byte) 0);
      remaining -= n;
      return n;
    }

    @Override
    public long skip(long n) {
      long skipped = Math.max(0, Math.min(n, remaining));
      remaining -= skipped;
      return skipped;
    }
  }
}
