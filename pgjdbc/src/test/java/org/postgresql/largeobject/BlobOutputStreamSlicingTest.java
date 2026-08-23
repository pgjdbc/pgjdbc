/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGConnection;
import org.postgresql.fastpath.Fastpath;
import org.postgresql.test.TestUtil;
import org.postgresql.util.ByteStreamWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A write too large for one {@code lowrite} goes out as several. The real bound needs an array of
 * about 2GiB, so these drive the same loop through the package-private slice bound instead.
 */
class BlobOutputStreamSlicingTest {
  private static final byte MARKER = 42;

  private Connection con;
  private LargeObjectManager lom;
  private Fastpath fastpath;

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    con.setAutoCommit(false);
    PGConnection pgCon = con.unwrap(PGConnection.class);
    lom = pgCon.getLargeObjectAPI();
    fastpath = pgCon.getFastpathAPI();
  }

  @AfterEach
  void tearDown() throws SQLException {
    TestUtil.closeDB(con);
  }

  /**
   * Counts the {@code lowrite} calls the stream issues, so a test can tell that the slice loop ran
   * more than once rather than assuming it from the byte count.
   */
  private static class CountingLargeObject extends LargeObject {
    private int writes;

    CountingLargeObject(Fastpath fp, long oid, int mode) throws SQLException {
      super(fp, oid, mode);
    }

    @Override
    public void write(byte[] buf) throws SQLException {
      writes++;
      super.write(buf);
    }

    @Override
    public void write(byte[] buf, int off, int len) throws SQLException {
      writes++;
      super.write(buf, off, len);
    }

    @Override
    public void write(ByteStreamWriter writer) throws SQLException {
      writes++;
      super.write(writer);
    }
  }

  @ParameterizedTest
  @CsvSource({
      // bufferSize, maxSlice, payload
      "1024,         8192,     30000",
      "64,           8192,     30000",
      "512,          2048,     10000",
      "8192,         8192,     30000",
      "512,          8192,     8191",
      "512,          8192,     8192",
      "512,          8192,     8193",
  })
  void aWriteLargerThanOneSliceStoresEveryByteInOrder(int bufferSize, int maxSlice, int length)
      throws Exception {
    long oid = lom.createLO();
    byte[] payload = payload(length);
    CountingLargeObject lo = new CountingLargeObject(fastpath, oid, LargeObjectManager.READWRITE);
    BlobOutputStream os = new BlobOutputStream(lo, bufferSize, maxSlice);
    os.write(payload, 0, payload.length);
    os.flush();

    int expectedSlices = (length + maxSlice - 1) / maxSlice;
    assertTrue(lo.writes >= expectedSlices,
        "expected at least " + expectedSlices + " lowrite calls for " + length
            + " bytes in slices of " + maxSlice + ", got " + lo.writes);

    lo.seek(0);
    assertArrayEquals(payload, lo.read(lo.size()), "large object contents");
    lo.close();
    lom.delete(oid);
  }

  /**
   * The bound the public constructors pick has to leave room for whatever the buffer adds to a
   * slice. Dropping that headroom is the defect this change was opened for, and no test that only
   * builds streams through the package-private constructor can see it.
   */
  @ParameterizedTest
  @CsvSource({
      // requested bufferSize, buffer the stream actually holds
      "1,           1",
      "3000,        2048",
      "8192,        8192",
      "524288,      524288",
      "1073741824,  536870912",
      "2147483647,  536870912",
      "-5,          1",
  })
  void thePublicConstructorLeavesTheBufferRoomInsideOneWrite(int bufferSize, int expectedBuffer)
      throws Exception {
    long oid = lom.createLO();
    try (LargeObject lo = lom.open(oid)) {
      BlobOutputStream os = new BlobOutputStream(lo, bufferSize);
      assertEquals(expectedBuffer, os.maxBufferSize, "buffer for requested size " + bufferSize);
      assertEquals(BlobOutputStream.maxSlice(expectedBuffer), os.maxSlice,
          "slice bound for requested size " + bufferSize);
      assertTrue(os.maxSlice > 0, "slice bound must be positive, got " + os.maxSlice);
      assertTrue((long) os.maxBufferSize + os.maxSlice <= BlobOutputStream.MAX_PAYLOAD,
          "buffer " + os.maxBufferSize + " plus slice " + os.maxSlice
              + " exceeds what one lowrite may carry");
    }
    lom.delete(oid);
  }

  /**
   * A write in progress excludes another thread's write, and slicing does not change that. This
   * cannot tell a lock taken per call from one taken per slice - the lock is not fair, so the
   * instant between two slices is not a moment a test can aim at - so the per-call lock is a
   * structural property instead: {@code write} takes it once and {@code writeSlice} does not take
   * it at all. What this does catch is locking lost altogether.
   */
  @Test
  void aWriteInProgressExcludesAnotherThread() throws Exception {
    long oid = lom.createLO();
    CountDownLatch insideFirstSlice = new CountDownLatch(1);
    CountDownLatch releaseFirstSlice = new CountDownLatch(1);
    CountDownLatch secondWriterFinished = new CountDownLatch(1);

    LargeObject lo = new LargeObject(fastpath, oid, LargeObjectManager.READWRITE) {
      @Override
      public void write(byte[] buf, int off, int len) throws SQLException {
        insideFirstSlice.countDown();
        try {
          releaseFirstSlice.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        super.write(buf, off, len);
      }
    };
    BlobOutputStream os = new BlobOutputStream(lo, 64, 8192);

    Thread slicer = new Thread(() -> {
      try {
        os.write(payload(30000), 0, 30000);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    slicer.start();
    assertTrue(insideFirstSlice.await(10, TimeUnit.SECONDS), "the first slice never started");

    Thread intruder = new Thread(() -> {
      try {
        os.write(new byte[]{MARKER}, 0, 1);
        secondWriterFinished.countDown();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    intruder.start();
    try {
      assertFalse(secondWriterFinished.await(300, TimeUnit.MILLISECONDS),
          "a second write got in while the first one was still in a slice");
    } finally {
      // Release it here, not after the assertion: a failed assertion would otherwise leave the
      // slicer parked inside the fake write until the connection is closed under it, and its
      // exception would land on an unjoined thread on top of the real failure.
      releaseFirstSlice.countDown();
    }
    slicer.join(30_000);
    intruder.join(30_000);
    assertTrue(secondWriterFinished.await(10, TimeUnit.SECONDS),
        "the second write never completed after the first one finished");

    os.close();
    byte[] expected = new byte[30001];
    System.arraycopy(payload(30000), 0, expected, 0, 30000);
    expected[30000] = MARKER;
    try (LargeObject stored = lom.open(oid)) {
      assertArrayEquals(expected, stored.read(stored.size()),
          "the second write landed inside the first one rather than after it");
    }
    lom.delete(oid);
  }

  /**
   * The seam exists to shrink the slice, never to grow it. A slice of zero would make the loop
   * spin without consuming anything, and one above the derived bound would put back the payload
   * this class exists to keep out, from inside the package that is supposed to prove it does not.
   */
  @Test
  void aSliceOutsideTheDerivedBoundIsRejected() throws Exception {
    long oid = lom.createLO();
    try (LargeObject lo = lom.open(oid)) {
      int bound = BlobOutputStream.maxSlice(1024);
      assertThrows(IllegalArgumentException.class, () -> new BlobOutputStream(lo, 1024, 0));
      assertThrows(IllegalArgumentException.class, () -> new BlobOutputStream(lo, 1024, -1));
      assertThrows(IllegalArgumentException.class,
          () -> new BlobOutputStream(lo, 1024, bound + 1));
      assertThrows(IllegalArgumentException.class,
          () -> new BlobOutputStream(lo, 1024, Integer.MAX_VALUE));
      assertEquals(bound, new BlobOutputStream(lo, 1024, bound).maxSlice,
          "the bound itself has to be accepted");
    }
    lom.delete(oid);
  }

  /**
   * The offset has to advance with the slice, not restart at the one the caller passed.
   */
  @ParameterizedTest
  @CsvSource({"1024, 4096, 20000, 7", "64, 2048, 9000, 1"})
  void slicingAdvancesThroughTheCallersArray(int bufferSize, int maxSlice, int length, int off)
      throws Exception {
    long oid = lom.createLO();
    byte[] source = payload(length + off);
    byte[] expected = new byte[length];
    System.arraycopy(source, off, expected, 0, length);
    try (LargeObject lo = lom.open(oid)) {
      BlobOutputStream os = new BlobOutputStream(lo, bufferSize, maxSlice);
      os.write(source, off, length);
      os.flush();
      lo.seek(0);
      assertArrayEquals(expected, lo.read(lo.size()), "large object contents");
    }
    lom.delete(oid);
  }

  /**
   * The payload has to be aperiodic over the lengths these tests use. A counter-derived one is
   * not: it repeats every 256 bytes, and every slice size here is a multiple of 256, so writing
   * the same slice twice would reproduce the expected bytes exactly and hide the defect.
   */
  private static byte[] payload(int length) {
    byte[] payload = new byte[length];
    new Random(length * 1103515245L + 12345).nextBytes(payload);
    return payload;
  }
}
