/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * {@link VisibleBufferedInputStream#skip(long)} returns {@code 0} and leaves the read position
 * unchanged for a count of zero or less, and skips the whole count while the buffer holds it.
 *
 * <p>The read position is {@link VisibleBufferedInputStream#getIndex()}, an offset into the array
 * {@link VisibleBufferedInputStream#getBuffer()} returns. A negative count used to be added to
 * that offset, after which {@link VisibleBufferedInputStream#available()} over-reported and the
 * next read failed with {@link ArrayIndexOutOfBoundsException}. {@code (int) Long.MIN_VALUE} is
 * {@code 0}, so the most negative count did not move the offset and {@code skip} returned
 * {@code Long.MIN_VALUE}.</p>
 *
 * <p>Both parameterized methods consume one byte before the skip, so {@code -1} leaves the offset
 * inside the buffer and {@code -2} drives it below zero.</p>
 */
class VisibleBufferedInputStreamSkipTest {
  /**
   * Payload the stream serves. Every byte differs from the one before it, so a skip that lands
   * at the wrong offset changes the byte read after it.
   */
  private static final byte[] DATA = {11, 22, 33, 44};

  private static VisibleBufferedInputStream bufferedStream() {
    return new VisibleBufferedInputStream(new ByteArrayInputStream(DATA), 1024);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, -2, Long.MIN_VALUE})
  void aCountOfZeroOrLessSkipsNothing(long count) throws IOException {
    VisibleBufferedInputStream in = bufferedStream();
    in.read();

    long skipped = in.skip(count);

    assertAll(
        () -> assertEquals(0, skipped, "bytes skipped"),
        () -> assertEquals(1, in.getIndex(), "read position"),
        () -> assertEquals(3, in.available(), "available()"));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, -2, Long.MIN_VALUE})
  void aCountOfZeroOrLessLeavesTheNextByteInPlace(long count) throws IOException {
    VisibleBufferedInputStream in = bufferedStream();
    in.read();
    in.skip(count);

    assertEquals(22, in.read(), "byte read after the skip");
  }

  @Test
  void aNegativeCountBeforeAnythingIsBufferedLeavesTheFirstByteInPlace() throws IOException {
    VisibleBufferedInputStream in = bufferedStream();

    in.skip(-1);

    assertEquals(11, in.read(), "byte read after skip(-1)");
  }

  @Test
  void anEmptyStreamStillReportsTheEndOfStreamAfterANegativeCount() throws IOException {
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new ByteArrayInputStream(new byte[0]), 1024);

    in.skip(-1);

    assertEquals(-1, in.read(), "read() after skip(-1) on a stream with no bytes");
  }

  @Test
  void aPositiveCountSkipsExactlyThatManyBytes() throws IOException {
    VisibleBufferedInputStream in = bufferedStream();
    in.read();

    long skipped = in.skip(1);

    assertAll(
        () -> assertEquals(1, skipped, "bytes skipped"),
        () -> assertEquals(33, in.read(), "byte read after the skip"));
  }
}
