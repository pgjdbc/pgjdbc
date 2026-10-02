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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.util.concurrent.TimeUnit;

class VisibleBufferedInputStreamTest {

  private static final int INITIAL_SIZE = 8192;

  /** One byte per read. */
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

  /** Endless non-zero bytes, in bulk so a scan to the maximum is quick. */
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

  /** Holds the given bytes and reports {@code reported} from {@code available()}. */
  private static class ReportsAvailable extends ByteArrayInputStream {
    private final int reported;

    ReportsAvailable(byte[] data, int reported) {
      super(data);
      this.reported = reported;
    }

    @Override
    public synchronized int available() {
      return reported;
    }
  }

  /** Holds the given bytes and throws from {@code available()}, as a closed socket does. */
  private static class ClosedSocketAvailable extends FilterInputStream {
    ClosedSocketAvailable(byte[] data) {
      super(new ByteArrayInputStream(data));
    }

    @Override
    public int available() throws IOException {
      throw new SocketException("Socket closed");
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void growsOncePerRequestNotOncePerRead() throws IOException {
    int wanted = 64 * 1024;
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new Trickle(), INITIAL_SIZE);

    assertTrue(in.ensureBytes(wanted));

    // One allocation for the request plus slack. Doubling per read would reach 128k.
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
    assertEquals(INITIAL_SIZE, in.getBuffer().length, "nothing should have been allocated");
  }

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

    // Shrunk by the skip, before any further read.
    assertEquals(INITIAL_SIZE, in.getBuffer().length);
    assertEquals(buffered % 251, in.read());
  }

  @Test
  void compactsWhenThatLeavesRoomToRead() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Bulk(), INITIAL_SIZE);
    assertTrue(in.ensureBytes(INITIAL_SIZE));
    byte[] before = in.getBuffer();
    in.skip(INITIAL_SIZE - 100);

    // 100 unread + 1000 wanted + 1024 slack fits in 8192.
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

    // 100 unread + 7500 wanted fits in 8192, but leaves under MINIMUM_READ spare.
    assertTrue(in.ensureBytes(7500));

    assertNotSame(before, in.getBuffer(), "should have grown rather than compacted");
  }

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
   * The same string one byte per read, where a scan that restarts after every refill is quadratic
   * and the timeout is the only way to detect it. In bulk there are too few refills for the
   * difference to matter.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void anUnterminatedStringTrickledOneByteAtATimeStopsAtTheMaximum() {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Trickle(), INITIAL_SIZE);

    assertThrows(IOException.class, () -> in.scanCStringLength());
    assertTrue(in.getBuffer().length <= VisibleBufferedInputStream.MAX_BUFFER_SIZE);
  }

  /** An unterminated C string scans to the buffer maximum, then stops. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void anUnterminatedStringStopsAtTheMaximum() {
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new Unterminated(), INITIAL_SIZE);

    assertThrows(IOException.class, () -> in.scanCStringLength());
    assertTrue(in.getBuffer().length <= VisibleBufferedInputStream.MAX_BUFFER_SIZE);
  }

  @Test
  void availableCountsOnlyTheBufferWhileItHoldsBytes() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(
        new ReportsAvailable(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, 500), INITIAL_SIZE);
    assertTrue(in.ensureBytes(8));
    in.read();

    assertEquals(7, in.available());
  }

  /** A closed socket throws from available(), and the bytes already buffered are still counted. */
  @Test
  void availableDoesNotCallAClosedWrappedSocketWhileTheBufferHoldsBytes() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(
        new ClosedSocketAvailable(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}), INITIAL_SIZE);
    assertTrue(in.ensureBytes(8));
    in.read();

    assertEquals(7, in.available());
  }

  @Test
  void availableReportsTheWrappedStreamOnceTheBufferIsEmpty() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(
        new ReportsAvailable(new byte[]{1, 2, 3}, 500), INITIAL_SIZE);

    assertEquals(500, in.available());
  }

  @Test
  void scanCStringLengthCountsTheTerminatorAndLeavesTheStringUnread() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(
        new ByteArrayInputStream(new byte[]{'a', 'b', 'c', 0, 'd'}), INITIAL_SIZE);

    int length = in.scanCStringLength();

    assertAll(
        () -> assertEquals(4, length, "length of \"abc\" and its terminator"),
        () -> assertEquals(0, in.getPosition(), "bytes consumed by the scan"),
        () -> assertEquals('a', in.getBuffer()[in.getIndex()], "byte at getIndex()"));
  }
}
