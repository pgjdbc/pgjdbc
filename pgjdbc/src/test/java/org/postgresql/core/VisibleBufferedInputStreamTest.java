/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.util.GT;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Drives {@link VisibleBufferedInputStream} from stub streams that never reach end of stream, so a
 * read or a scan that failed to stop on its own would run until the {@link Timeout} fired. The
 * tests cover how the buffer sizes itself: it grows to hold what a read asks for, refuses to grow
 * past {@link VisibleBufferedInputStream#MAX_BUFFER_SIZE}, and returns to its initial size once it
 * is drained. Growth and compaction both keep the bytes already buffered.
 */
class VisibleBufferedInputStreamTest {

  private static final int INITIAL_SIZE = 8192;

  /**
   * The refusal {@link VisibleBufferedInputStream#growBuffer} builds for a request it will not
   * allocate for, built here with {@link GT#tr} as the driver builds it, so the tests hold in any
   * locale.
   */
  private static String refusalFor(long required) {
    return GT.tr("Backend asked for {0} bytes of buffer, the maximum is {1} bytes.",
        String.valueOf(required), String.valueOf(VisibleBufferedInputStream.MAX_BUFFER_SIZE));
  }

  /** Returns one byte per read, however many the caller asked for. */
  private static class Trickle extends InputStream {
    @Override
    public int read() {
      return 'x';
    }

    @Override
    public int read(byte[] to, int off, int len) {
      to[off] = 'x';
      return 1;
    }
  }

  /**
   * Fills every read with non-zero bytes, so a scan for a string terminator never finds one. It
   * fills the whole request, so a scan reaches the buffer maximum in few reads.
   */
  private static class Unterminated extends InputStream {
    @Override
    public int read() {
      return 'x';
    }

    @Override
    public int read(byte[] to, int off, int len) {
      for (int i = 0; i < len; i++) {
        to[off + i] = 'x';
      }
      return len;
    }
  }

  /**
   * Fills every read. The byte at stream position {@code p} is {@code p % 251}, so a test can tell
   * which position a buffered byte came from.
   */
  private static class Bulk extends InputStream {
    private long pos;

    @Override
    public int read() {
      return (int) (pos++ % 251);
    }

    @Override
    public int read(byte[] to, int off, int len) {
      for (int i = 0; i < len; i++) {
        to[off + i] = (byte) (pos++ % 251);
      }
      return len;
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void growsOncePerRequestNotOncePerRead() throws IOException {
    int wanted = 64 * 1024;
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new Trickle(), INITIAL_SIZE);

    assertTrue(in.ensureBytes(wanted), "the stub must deliver the whole request");

    // One allocation, sized to the request plus MINIMUM_READ (1024). Growing by doubling instead
    // would allocate more than once for the one request.
    assertEquals(wanted + 1024, in.getBuffer().length, "buffer length after one request");
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void refusesToGrowPastTheMaximum() {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Bulk(), INITIAL_SIZE);

    IOException e = assertThrows(IOException.class,
        () -> in.ensureBytes(VisibleBufferedInputStream.MAX_BUFFER_SIZE + 1));

    assertAll(
        () -> assertEquals(refusalFor(VisibleBufferedInputStream.MAX_BUFFER_SIZE + 1L),
            e.getMessage()),
        () -> assertEquals(INITIAL_SIZE, in.getBuffer().length,
            "the buffer must stay at its initial size"));
  }

  /**
   * A 4 byte length field can declare up to {@link Integer#MAX_VALUE}, and the driver accepts a
   * message of up to {@link PGStream#MAX_MESSAGE_LENGTH}, just under a gigabyte. Both are far
   * above the 32 megabytes the buffer will grow to, so both leave it at its initial size.
   */
  @ParameterizedTest(name = "a request for {0} bytes")
  @ValueSource(ints = {Integer.MAX_VALUE, PGStream.MAX_MESSAGE_LENGTH})
  void refusesTheLargestDeclarableLengths(int declared) {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Bulk(), INITIAL_SIZE);

    IOException e = assertThrows(IOException.class, () -> in.ensureBytes(declared));

    assertAll(
        () -> assertEquals(refusalFor(declared), e.getMessage()),
        () -> assertEquals(INITIAL_SIZE, in.getBuffer().length,
            "the buffer must stay at its initial size"));
  }

  @Test
  void stillDoublesForOrdinaryReads() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Bulk(), INITIAL_SIZE);

    assertTrue(in.ensureBytes(INITIAL_SIZE), "the stub must fill the buffer");
    assertEquals(INITIAL_SIZE, in.getBuffer().length, "a request that fits must not grow it");
    assertTrue(in.ensureBytes(INITIAL_SIZE + 1), "the stub must deliver one byte more");

    assertEquals(INITIAL_SIZE * 2, in.getBuffer().length, "buffer length after the growth");
  }

  /** A buffer grown for one message returns to its initial size the moment it is fully drained. */
  @Test
  void shrinksBackAsSoonAsAnOutsizedReadIsDrained() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Bulk(), INITIAL_SIZE);
    assertTrue(in.ensureBytes(20000), "the stub must deliver the whole request");
    assertTrue(in.getBuffer().length > INITIAL_SIZE, "the request must have grown the buffer");
    int buffered = in.available();
    in.skip(buffered);

    // The skip drains the buffer, so the shrink happens there rather than on the next read. The
    // read after it returns the byte at the stream position the skip stopped at.
    assertAll(
        () -> assertEquals(INITIAL_SIZE, in.getBuffer().length, "buffer length after the skip"),
        () -> assertEquals(buffered % 251, in.read(), "first byte after the skip"));
  }

  /**
   * A read drains the buffer as a skip does, so the shrink happens on that path as well. The read
   * takes exactly what is buffered, which leaves the buffer empty without touching the stream.
   */
  @Test
  void shrinksBackWhenAnOutsizedReadIsDrainedByARead() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Bulk(), INITIAL_SIZE);
    assertTrue(in.ensureBytes(20000), "the stub must deliver the whole request");
    int buffered = in.available();
    byte[] to = new byte[buffered];

    assertEquals(buffered, in.read(to, 0, buffered), "bytes returned by the read");

    assertAll(
        () -> assertEquals(INITIAL_SIZE, in.getBuffer().length, "buffer length after the read"),
        () -> assertEquals(0, to[0], "the read must start at stream position 0"),
        () -> assertEquals(buffered % 251, in.read(), "first byte after the read"));
  }

  @Test
  void compactsWhenThatLeavesRoomToRead() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Bulk(), INITIAL_SIZE);
    assertTrue(in.ensureBytes(INITIAL_SIZE));
    byte[] before = in.getBuffer();
    in.skip(INITIAL_SIZE - 100);

    // This asks for 900 more than the 100 already unread, and 1000 + MINIMUM_READ fits in 8192.
    assertTrue(in.ensureBytes(1000), "the stub must deliver the whole request");

    assertSame(before, in.getBuffer(), "should have compacted rather than allocated");
  }

  /** Compacting to leave less than MINIMUM_READ free would mean a socket read of a few bytes. */
  @Test
  void growsWhenCompactionWouldLeaveNoRoomToRead() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Bulk(), INITIAL_SIZE);
    assertTrue(in.ensureBytes(INITIAL_SIZE));
    byte[] before = in.getBuffer();
    in.skip(INITIAL_SIZE - 100);

    // This asks for 7400 more than the 100 already unread. The 7500 fits in 8192, but 7500 +
    // MINIMUM_READ does not.
    assertTrue(in.ensureBytes(7500), "the stub must deliver the whole request");

    assertNotSame(before, in.getBuffer(), "should have grown rather than compacted");
  }

  /**
   * The skip before the growth leaves the unread bytes at a non-zero index, so the growth has to
   * copy from the read position rather than from the start of the buffer.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void keepsTheDataAcrossGrowth() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Bulk(), INITIAL_SIZE);
    assertTrue(in.ensureBytes(10), "the stub must deliver the first request");
    in.skip(10);

    assertTrue(in.ensureBytes(20000), "the stub must deliver the whole request");

    byte[] buffer = in.getBuffer();
    for (int i = 0; i < 20000; i++) {
      assertEquals((byte) ((i + 10) % 251), buffer[in.getIndex() + i], "byte " + i);
    }
  }

  /**
   * Scans the same unterminated string as {@link #anUnterminatedStringStopsAtTheMaximum()}, from a
   * stream that returns one byte per read. At that rate a scan that restarted after every refill
   * would take quadratic time, and only the timeout would report it. The bulk stream refills too
   * few times for the difference to show.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void anUnterminatedStringTrickledOneByteAtATimeStopsAtTheMaximum() {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Trickle(), INITIAL_SIZE);

    IOException e = assertThrows(IOException.class, () -> in.scanCStringLength());

    assertAll(
        // The scan itself has no limit to report, so what it reports is the buffer refusing to
        // grow. The maximum is substituted into the message, so this holds in any locale.
        () -> assertTrue(e.getMessage()
            .contains(String.valueOf(VisibleBufferedInputStream.MAX_BUFFER_SIZE)), e.getMessage()),
        () -> assertTrue(in.getBuffer().length <= VisibleBufferedInputStream.MAX_BUFFER_SIZE,
            "the buffer must not have grown past its maximum"));
  }

  /**
   * The scan has no length limit of its own, so what stops it is the buffer refusing to grow past
   * {@link VisibleBufferedInputStream#MAX_BUFFER_SIZE}.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void anUnterminatedStringStopsAtTheMaximum() {
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new Unterminated(), INITIAL_SIZE);

    IOException e = assertThrows(IOException.class, () -> in.scanCStringLength());

    assertAll(
        () -> assertTrue(e.getMessage()
            .contains(String.valueOf(VisibleBufferedInputStream.MAX_BUFFER_SIZE)), e.getMessage()),
        () -> assertTrue(in.getBuffer().length <= VisibleBufferedInputStream.MAX_BUFFER_SIZE,
            "the buffer must not have grown past its maximum"));
  }
}
