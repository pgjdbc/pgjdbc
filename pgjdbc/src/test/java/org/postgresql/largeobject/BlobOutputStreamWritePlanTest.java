/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BlobOutputStreamWritePlanTest {
  /**
   * The total this method receives is {@code bufferPosition + len}, which exceeds int range for a
   * len near {@link Integer#MAX_VALUE}. It used to be computed in int, so it wrapped negative, and
   * the remainder of a negative number is negative in Java: the caller then subtracted a negative
   * tail, asked for a buffer sized from a negative total, and got an empty one.
   */
  @ParameterizedTest
  @CsvSource({
      // total,       maxBufferSize, expected tail
      "2147483747,    524288,        99",
      "2147483648,    524288,        0",
      "3000000000,    524288,        7680",
      "2147483747,    4096,          99",
      "2147483747,    1024,          0",
  })
  void tailLengthHoldsBeyondIntRange(long total, int maxBufferSize, int expected) {
    assertEquals(expected, BlobOutputStream.tailLength(total, maxBufferSize));
  }

  /**
   * A buffer smaller than a large object row cannot carry a remainder, so nothing is held back.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 2, 1024, 2047})
  void nothingIsHeldBackByABufferSmallerThanARow(int maxBufferSize) {
    assertEquals(0, BlobOutputStream.tailLength(5000, maxBufferSize));
  }

  @ParameterizedTest
  @ValueSource(ints = {2048, 4096, 8191})
  void mediumBuffersAlignToTwoKibibytes(int maxBufferSize) {
    assertEquals(904, BlobOutputStream.tailLength(5000, maxBufferSize));
  }

  @ParameterizedTest
  @ValueSource(ints = {8192, 16384, 512 * 1024, 1 << 29})
  void largeBuffersAlignToEightKibibytes(int maxBufferSize) {
    assertEquals(5000, BlobOutputStream.tailLength(5000, maxBufferSize));
  }

  /**
   * Whatever is held back has to fit in the buffer that carries it over, and has to be a length,
   * not a debt. Both properties are what the caller relies on when it sizes the next buffer.
   */
  @Test
  void theTailIsAlwaysANonNegativeLengthTheBufferCanHold() {
    long[] totals = {0, 1, 2047, 2048, 8191, 8192, 8193, 100000,
        Integer.MAX_VALUE, (long) Integer.MAX_VALUE + 1, (long) Integer.MAX_VALUE + 8192,
        2L * Integer.MAX_VALUE};
    int[] maxBufferSizes = {1, 2, 1024, 2048, 4096, 8192, 16384, 512 * 1024, 1 << 29};
    int checked = 0;
    for (long total : totals) {
      for (int maxBufferSize : maxBufferSizes) {
        int tail = BlobOutputStream.tailLength(total, maxBufferSize);
        assertTrue(tail >= 0,
            "tail " + tail + " for total " + total + " and buffer " + maxBufferSize);
        assertTrue(tail <= maxBufferSize,
            "tail " + tail + " does not fit buffer " + maxBufferSize + " for total " + total);
        assertTrue(tail <= total,
            "tail " + tail + " exceeds the total " + total);
        checked++;
      }
    }
    assertEquals(totals.length * maxBufferSizes.length, checked, "combinations exercised");
    assertTrue(checked >= 100, "grid degenerated to " + checked + " combinations");
  }

  /**
   * The three parts of a write plan have to add up to the bytes available, and the two that go out
   * together have to fit what one {@code lowrite} may carry. Slicing the caller's array is what
   * keeps that true, so the grid runs the totals a slice can actually produce.
   */
  @Test
  void thePlanAddsUpAndItsPayloadFitsOneWrite() {
    int[] maxBufferSizes = {1, 2, 1024, 2048, 4096, 8192, 16384, 512 * 1024, 1 << 29};
    int checked = 0;
    int sawFullPayload = 0;
    for (int maxBufferSize : maxBufferSizes) {
      long maxSlice = BlobOutputStream.maxSlice(maxBufferSize);
      assertTrue(maxSlice > 0, "slice for buffer " + maxBufferSize);
      for (long bufferPosition : new long[]{0, 1, maxBufferSize - 1, maxBufferSize}) {
        for (long len : new long[]{0, 1, 8191, 8192, maxSlice - 1, maxSlice}) {
          long totalData = bufferPosition + len;
          int tail = BlobOutputStream.tailLength(totalData, maxBufferSize);
          int fromBuffer = BlobOutputStream.writeFromBuffer(totalData, (int) bufferPosition, tail);
          int fromB = BlobOutputStream.writeFromB(totalData, fromBuffer, tail);

          assertEquals(totalData, (long) fromBuffer + fromB + tail,
              "plan for buffer " + maxBufferSize + " at " + bufferPosition + " plus " + len);
          assertTrue(fromBuffer >= 0 && fromB >= 0,
              "negative part in the plan: " + fromBuffer + " and " + fromB);
          assertTrue(fromBuffer <= bufferPosition, "took more than the buffer holds");
          assertTrue(fromB <= len, "took more than the caller offered");
          assertTrue((long) fromBuffer + fromB <= BlobOutputStream.MAX_PAYLOAD,
              "payload " + ((long) fromBuffer + fromB) + " exceeds what one lowrite may carry");
          if ((long) fromBuffer + fromB > BlobOutputStream.MAX_PAYLOAD / 2) {
            sawFullPayload++;
          }
          checked++;
        }
      }
    }
    assertEquals(maxBufferSizes.length * 4 * 6, checked, "combinations exercised");
    assertTrue(sawFullPayload > 0,
        "no combination reached a payload large enough to test the int bound");
  }

  /**
   * A {@code lowrite} passes the bytes as a {@code bytea}, and a varlena cannot exceed
   * PostgreSQL's {@code MaxAllocSize} of 0x3fffffff. A payload past that is refused by the server
   * however well formed the message carrying it is, so splitting a large write at a larger bound
   * would produce a first call that never lands, and the split would buy nothing.
   */
  @Test
  void onePayloadFitsAVarlena() {
    int maxAllocSize = 0x3fffffff;
    assertTrue(BlobOutputStream.MAX_PAYLOAD <= maxAllocSize,
        "payload bound " + BlobOutputStream.MAX_PAYLOAD + " exceeds MaxAllocSize " + maxAllocSize);
    assertTrue(BlobOutputStream.MAX_PAYLOAD > maxAllocSize / 2,
        "payload bound " + BlobOutputStream.MAX_PAYLOAD
            + " gives away more than half of what one write may carry");
    assertEquals(0, BlobOutputStream.MAX_PAYLOAD % 8192,
        "the payload bound has to keep the row alignment");
    assertTrue(BlobOutputStream.maxSlice(BlobOutputStream.MAX_BUFFER_SIZE) >= 8192,
        "the largest buffer must still leave a slice worth carrying, got "
            + BlobOutputStream.maxSlice(BlobOutputStream.MAX_BUFFER_SIZE));
  }
}
