/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A {@link BlobOutputStream} sends a write longer than its slice as several {@code lowrite}
 * calls of at most one slice from the caller's array each, and the large object receives the
 * caller's bytes in order.
 *
 * <p>The slice the public constructors derive needs an array of about 1 GiB, so these tests set a
 * smaller one through the package-private constructor.</p>
 */
@Timeout(30)
class BlobOutputStreamSlicingTest {
  private static final int SLICE = 8192;

  static Stream<Arguments> aWriteLongerThanTheSliceGoesOutOneSliceAtATime() {
    return Stream.of(
        arguments(8191, new int[]{8191}),
        arguments(8192, new int[]{8192}),
        arguments(8193, new int[]{8192, 1}),
        arguments(30000, new int[]{8192, 8192, 8192, 5424}));
  }

  /**
   * A 1 KiB buffer holds back no remainder for alignment, so each slice goes out as a
   * {@code lowrite} of its own.
   */
  @ParameterizedTest
  @MethodSource
  void aWriteLongerThanTheSliceGoesOutOneSliceAtATime(int length, int[] expectedLowrites)
      throws Exception {
    RecordingLargeObject lo = new RecordingLargeObject();
    BlobOutputStream os = new BlobOutputStream(lo, 1024, SLICE);
    byte[] payload = payload(length);

    os.write(payload, 0, length);
    os.flush();

    assertAll(
        () -> assertArrayEquals(expectedLowrites, lo.lowriteLengths(), "lowrite lengths"),
        () -> assertArrayEquals(payload, lo.contents(), "large object contents"));
  }

  @Test
  void aSlicedWriteStartsAtTheCallersOffset() throws Exception {
    RecordingLargeObject lo = new RecordingLargeObject();
    BlobOutputStream os = new BlobOutputStream(lo, 1024, SLICE);
    byte[] source = payload(20007);

    os.write(source, 7, 20000);
    os.flush();

    assertArrayEquals(Arrays.copyOfRange(source, 7, 20007), lo.contents(),
        "large object contents after write(source, 7, 20000)");
  }

  /**
   * An 8 KiB buffer that holds 100 bytes sends them ahead of a full slice and keeps the last 100
   * bytes of the slice to stay on a row boundary, so each full slice goes out as one 8 KiB
   * {@code lowrite} of buffered bytes followed by the caller's. The last, shorter slice stays in
   * the buffer until the flush.
   */
  @Test
  void eachSliceGoesOutBehindTheBytesTheBufferHolds() throws Exception {
    RecordingLargeObject lo = new RecordingLargeObject();
    BlobOutputStream os = new BlobOutputStream(lo, 8192, SLICE);
    byte[] data = payload(20100);

    os.write(data, 0, 100);
    os.write(data, 100, 20000);
    os.flush();

    assertAll(
        () -> assertArrayEquals(new int[]{8192, 8192, 3716}, lo.lowriteLengths(),
            "lowrite lengths"),
        () -> assertArrayEquals(data, lo.contents(), "large object contents"));
  }

  /**
   * A failure in the second slice reports the length the caller asked for, not the part of it
   * that was left.
   */
  @Test
  void aFailedSlicedWriteReportsTheRequestedLength() throws Exception {
    SQLException failure = new SQLException("lowrite failed");
    RecordingLargeObject lo = new RecordingLargeObject() {
      private int lowrites;

      @Override
      protected void beforeLowrite() throws SQLException {
        if (++lowrites == 2) {
          throw failure;
        }
      }
    };
    BlobOutputStream os = new BlobOutputStream(lo, 1024, SLICE);
    byte[] data = payload(20000);

    IOException e = assertThrows(IOException.class, () -> os.write(data, 0, 20000));

    // GT.tr formats the length with MessageFormat, which groups digits by the default locale
    String requestedLength = MessageFormat.format("{0}", 20000);
    assertAll(
        () -> assertSame(failure, e.getCause(), "cause of the IOException"),
        () -> assertTrue(e.getMessage().contains(requestedLength),
            () -> "message should carry the requested length " + requestedLength + ": "
                + e.getMessage()));
  }

  static IntStream aSliceOutsideOneToTheDerivedBoundIsRefused() {
    // maxSlice(1024) is 1073675264
    return IntStream.of(0, 1073675265);
  }

  @ParameterizedTest
  @MethodSource
  void aSliceOutsideOneToTheDerivedBoundIsRefused(int maxSlice) throws Exception {
    RecordingLargeObject lo = new RecordingLargeObject();
    assertThrows(IllegalArgumentException.class, () -> new BlobOutputStream(lo, 1024, maxSlice));
  }

  static IntStream aSliceFromOneToTheDerivedBoundIsAccepted() {
    return IntStream.of(1, 1073675264);
  }

  @ParameterizedTest
  @MethodSource
  void aSliceFromOneToTheDerivedBoundIsAccepted(int maxSlice) throws Exception {
    RecordingLargeObject lo = new RecordingLargeObject();
    assertDoesNotThrow(() -> new BlobOutputStream(lo, 1024, maxSlice));
  }

  /**
   * The first write is held inside its first {@code lowrite}, with three slices still to go, and a
   * second write starts on another thread. That write must wait for the lock, and its byte must
   * land after all of the first write's bytes.
   *
   * <p>This does not tell a lock held across the slices from one taken per slice: the code between
   * two slices calls nothing a test can hold open.</p>
   */
  @Test
  void aSecondWriteWaitsWhileTheFirstOneIsInsideALowrite() throws Exception {
    CountDownLatch insideLowrite = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    RecordingLargeObject lo = new RecordingLargeObject() {
      @Override
      protected void beforeLowrite() throws SQLException {
        if (insideLowrite.getCount() == 0) {
          return;
        }
        insideLowrite.countDown();
        try {
          release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new SQLException(e);
        }
      }
    };
    BlobOutputStream os = new BlobOutputStream(lo, 64, SLICE);
    byte[] data = payload(30001);
    FutureTask<Void> first = new FutureTask<>(() -> {
      os.write(data, 0, 30000);
      return null;
    });
    FutureTask<Void> second = new FutureTask<>(() -> {
      os.write(data, 30000, 1);
      return null;
    });
    Thread secondThread = new Thread(second, "second writer");

    new Thread(first, "first writer").start();
    try {
      assertTrue(insideLowrite.await(30, TimeUnit.SECONDS), "the first write did not reach a lowrite within 30 seconds");
      secondThread.start();
      assertEquals(Thread.State.WAITING, awaitParkedOrTerminated(secondThread),
          "state of the second writer while the first one is inside a lowrite");
    } finally {
      release.countDown();
    }
    first.get(30, TimeUnit.SECONDS);
    second.get(30, TimeUnit.SECONDS);
    os.flush();

    assertArrayEquals(data, lo.contents(), "large object contents");
  }

  /**
   * Waits until the thread parks or ends, and returns the state it reached. A thread that is still
   * running after 30 seconds is returned in its last state.
   */
  private static Thread.State awaitParkedOrTerminated(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    Thread.State state = thread.getState();
    while (state != Thread.State.WAITING && state != Thread.State.TERMINATED
        && System.nanoTime() - deadline < 0) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
      state = thread.getState();
    }
    return state;
  }

  /**
   * Returns seeded random bytes, so a slice sent twice or sent from the wrong offset changes the
   * contents.
   */
  private static byte[] payload(int length) {
    byte[] payload = new byte[length];
    new Random(length).nextBytes(payload);
    return payload;
  }
}
