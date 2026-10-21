/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * The read buffer grows to hold what a read asks for, refuses to grow past
 * {@link VisibleBufferedInputStream#MAX_BUFFER_SIZE}, and returns to its initial size once it is
 * drained. Growth and compaction both keep the bytes already buffered.
 *
 * <p>None of the stub streams here ever reaches end of stream, so a read or a scan that failed to
 * stop on its own would run until the {@link Timeout} fired.</p>
 */
class VisibleBufferedInputStreamTest {

  private static final int INITIAL_SIZE = 8192;

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

    assertTrue(in.ensureBytes(wanted));

    // One allocation, sized to the request plus MINIMUM_READ (1024). Growing by doubling instead
    // would allocate more than once for the one request.
    assertEquals(wanted + 1024, in.getBuffer().length);
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void refusesToGrowPastTheMaximum() {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Bulk(), INITIAL_SIZE);

    IOException e = assertThrows(IOException.class,
        () -> in.ensureBytes(VisibleBufferedInputStream.MAX_BUFFER_SIZE + 1));

    assertTrue(e.getMessage().contains(String.valueOf(VisibleBufferedInputStream.MAX_BUFFER_SIZE)),
        e.getMessage());
    assertEquals(INITIAL_SIZE, in.getBuffer().length, "the buffer must stay at its initial size");
  }

  /**
   * A 4 byte length field can declare up to {@link Integer#MAX_VALUE}, and the driver accepts a
   * message of up to {@link PGStream#MAX_MESSAGE_LENGTH}, just under a gigabyte. Both are far
   * above the 32 megabytes the buffer will grow to, so both leave it at its initial size.
   */
  @Test
  void refusesTheLargestDeclarableLengths() {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Bulk(), INITIAL_SIZE);

    assertThrows(IOException.class, () -> in.ensureBytes(Integer.MAX_VALUE));
    assertThrows(IOException.class, () -> in.ensureBytes(PGStream.MAX_MESSAGE_LENGTH));
    assertEquals(INITIAL_SIZE, in.getBuffer().length);
  }

  @Test
  void stillDoublesForOrdinaryReads() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Bulk(), INITIAL_SIZE);

    assertTrue(in.ensureBytes(INITIAL_SIZE));
    assertEquals(INITIAL_SIZE, in.getBuffer().length);
    assertTrue(in.ensureBytes(INITIAL_SIZE + 1));

    assertEquals(INITIAL_SIZE * 2, in.getBuffer().length);
  }

  /** A buffer grown for one message returns to its initial size the moment it is fully drained. */
  @Test
  void shrinksBackAsSoonAsAnOutsizedReadIsDrained() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Bulk(), INITIAL_SIZE);
    assertTrue(in.ensureBytes(20000));
    assertTrue(in.getBuffer().length > INITIAL_SIZE);
    int buffered = in.available();
    in.skip(buffered);

    // The skip drains the buffer, so the shrink happens there rather than on the next read. The
    // read after it returns the byte at the stream position the skip stopped at.
    assertEquals(INITIAL_SIZE, in.getBuffer().length);
    assertEquals(buffered % 251, in.read());
  }

  @Test
  void compactsWhenThatLeavesRoomToRead() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Bulk(), INITIAL_SIZE);
    assertTrue(in.ensureBytes(INITIAL_SIZE));
    byte[] before = in.getBuffer();
    in.skip(INITIAL_SIZE - 100);

    // This asks for 900 more than the 100 already unread, and 1000 + MINIMUM_READ fits in 8192.
    assertTrue(in.ensureBytes(1000));

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
    assertTrue(in.ensureBytes(7500));

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
    assertTrue(in.ensureBytes(10));
    in.skip(10);

    assertTrue(in.ensureBytes(20000));

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

    assertThrows(IOException.class, () -> in.scanCStringLength());
    assertTrue(in.getBuffer().length <= VisibleBufferedInputStream.MAX_BUFFER_SIZE);
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

    assertThrows(IOException.class, () -> in.scanCStringLength());
    assertTrue(in.getBuffer().length <= VisibleBufferedInputStream.MAX_BUFFER_SIZE);
  }
}
