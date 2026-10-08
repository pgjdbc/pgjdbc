/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.stream.Stream;

/**
 * A {@link BlobOutputStream} built through its public constructors keeps every {@code lowrite}
 * within the largest function call message PostgreSQL reads, and ends a write that overflows its
 * buffer on a large object row boundary.
 */
class BlobOutputStreamTest {
  /**
   * PostgreSQL's {@code PQ_LARGE_MESSAGE_LIMIT}, {@code MaxAllocSize - 1}: the server closes the
   * connection on a longer function call message.
   */
  private static final int PQ_LARGE_MESSAGE_LIMIT = 0x3fffffff - 1;

  /**
   * Room left for the rest of a {@code lowrite} message, which is a few dozen bytes.
   */
  private static final int MESSAGE_OVERHEAD = 1024;

  @ParameterizedTest
  @CsvSource({
      // requested,  held
      "-5,           1",
      "0,            1",
      "1000,         512",
      "524288,       524288",
      "536870912,    536870912",
      "536870913,    536870912",
      "1073741823,   536870912",
      "1073741824,   536870912",
      "2147483647,   536870912",
  })
  void theBufferSizeIsRoundedDownToAPowerOfTwoNoLargerThan512MiB(int requested, int held)
      throws Exception {
    assertEquals(held, new BlobOutputStream(new RecordingLargeObject(), requested).maxBufferSize);
  }

  /**
   * The buffer and the slice taken from the caller's array go out together in one {@code lowrite},
   * so their sum plus the rest of the message has to fit the server limit.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 1024, 8192, 524288, 536870912, Integer.MAX_VALUE})
  void aFullBufferAndAFullSliceFitOneFunctionCallMessage(int bufferSize) throws Exception {
    BlobOutputStream os = new BlobOutputStream(new RecordingLargeObject(), bufferSize);
    long largestPayload = (long) os.maxBufferSize + os.maxSlice;
    assertTrue(largestPayload <= PQ_LARGE_MESSAGE_LIMIT - MESSAGE_OVERHEAD,
        () -> "buffer " + os.maxBufferSize + " plus slice " + os.maxSlice + " is "
            + largestPayload + ", which leaves less than " + MESSAGE_OVERHEAD
            + " bytes below PQ_LARGE_MESSAGE_LIMIT " + PQ_LARGE_MESSAGE_LIMIT);
  }

  static Stream<Arguments> aWriteThatOverflowsTheBufferEndsOnARowBoundary() {
    return Stream.of(
        arguments(1024, new int[]{13000}),
        arguments(2048, new int[]{12288, 712}),
        arguments(4096, new int[]{12288, 712}),
        arguments(8192, new int[]{8192, 4808}));
  }

  /**
   * A write of 13000 bytes overflows each of these buffers. The stream sends it up to the last
   * row boundary and keeps the rest buffered until the flush: the row is 8 KiB for a buffer of
   * 8 KiB, 2 KiB for a buffer of 2 KiB or 4 KiB, and a smaller buffer sends everything at once.
   */
  @ParameterizedTest
  @MethodSource
  void aWriteThatOverflowsTheBufferEndsOnARowBoundary(int bufferSize, int[] expectedLowrites)
      throws Exception {
    RecordingLargeObject lo = new RecordingLargeObject();
    BlobOutputStream os = new BlobOutputStream(lo, bufferSize);
    byte[] payload = payload(13000);

    os.write(payload, 0, payload.length);
    os.flush();

    assertAll(
        () -> assertArrayEquals(expectedLowrites, lo.lowriteLengths(), "lowrite lengths"),
        () -> assertArrayEquals(payload, lo.contents(), "large object contents"));
  }

  /**
   * Returns seeded random bytes, so bytes sent twice or sent from the wrong offset change the
   * contents.
   */
  private static byte[] payload(int length) {
    byte[] payload = new byte[length];
    new Random(length).nextBytes(payload);
    return payload;
  }
}
