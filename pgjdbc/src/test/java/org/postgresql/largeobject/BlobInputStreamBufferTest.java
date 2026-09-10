/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGConnection;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * How {@link BlobInputStream} sizes the buffer it asks the server to fill.
 */
class BlobInputStreamBufferTest {
  private Connection con;
  private LargeObjectManager lom;
  private long loid;

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    con.setAutoCommit(false);
    lom = con.unwrap(PGConnection.class).getLargeObjectAPI();
    loid = lom.createLO();
    try (LargeObject blob = lom.open(loid, LargeObjectManager.WRITE)) {
      // Larger than one full buffer, so an unlimited stream has more to fetch than any limit allows
      blob.write(new byte[4 * BlobInputStream.INITIAL_BUFFER_SIZE]);
    }
  }

  @AfterEach
  void tearDown() throws SQLException {
    TestUtil.closeDB(con);
  }

  /**
   * A limited stream asks the server only for the bytes it may serve. A stream built by
   * {@code Blob.getBinaryStream(pos, length)} used to pull a whole 64 KiB buffer to answer a
   * one-byte read on a ten-byte stream, and throw the rest away.
   *
   * @param limit the number of bytes the stream may serve
   */
  @ParameterizedTest
  @ValueSource(longs = {1, 10, 1024})
  void singleByteReadStaysWithinLimit(long limit) throws Exception {
    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      InputStream bis = blob.getInputStream(limit);
      assertEquals(0, bis.read());
      assertEquals(limit, blob.tell(),
          () -> "bytes fetched from the server for a stream limited to " + limit);
    }
  }

  /**
   * The same holds for the array read, which sizes its refill from the requested length rather
   * than from the limit.
   *
   * @param limit the number of bytes the stream may serve
   */
  @ParameterizedTest
  @ValueSource(longs = {1, 10, 1024})
  void arrayReadStaysWithinLimit(long limit) throws Exception {
    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      InputStream bis = blob.getInputStream(limit);
      assertEquals(1, bis.read(new byte[8], 0, 1));
      assertEquals(limit, blob.tell(),
          () -> "bytes fetched from the server for a stream limited to " + limit);
    }
  }

  /**
   * An unlimited stream keeps fetching a whole buffer, which is the behaviour the limit clamp must
   * not disturb.
   */
  @Test
  void unlimitedReadStillFetchesAWholeBuffer() throws Exception {
    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      InputStream bis = blob.getInputStream();
      assertEquals(0, bis.read());
      assertEquals(BlobInputStream.INITIAL_BUFFER_SIZE, blob.tell());
    }
  }

  /**
   * A buffer size that is not positive makes a stream that may read nothing per request, and it
   * reports end of stream on its first read, which a caller cannot tell from an empty large
   * object. {@link LargeObject#getInputStream(int, long)} is public, so the size is refused where
   * it arrives rather than left to produce that.
   *
   * @param bsize the invalid buffer size
   */
  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void rejectsNonPositiveBufferSize(int bsize) throws Exception {
    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      assertThrows(IllegalArgumentException.class, () -> blob.getInputStream(bsize, -1));
    }
  }

  /**
   * The growth policy must never hand back a size outside its ceiling, and must never shrink.
   * Doubling used to overflow in two places, with a different symptom each time.
   * {@code highestOneBit(len * 2)} wraps for a read request above 2^30, where
   * {@code highestOneBit} of a negative int is {@link Integer#MIN_VALUE}, and that negative size
   * passed straight through {@code Math.min} to the caller. {@code lastBufferSize * 2} wraps once
   * a caller-supplied ceiling lets the buffer past 2^30, and there the size stayed positive but
   * collapsed: the wrapped value made the second branch fire, which recomputed from
   * {@code len} alone, so a stream that had grown to 1 GiB dropped back to 2 bytes and started
   * over. Only the monotonic assertion sees that one.
   *
   * @param maxBufferSize buffer ceiling the stream was built with
   * @param len read request to size a buffer for
   */
  @ParameterizedTest
  @CsvSource({
      "524288, 1",
      "524288, 524288",
      "524288, 1073741824",
      "524288, 2147483647",
      "2147483647, 1",
      "2147483647, 1073741823",
      "2147483647, 1073741824",
      "2147483647, 2147483647",
      "1, 2147483647",
  })
  void bufferSizeStaysInRange(int maxBufferSize, int len) throws Exception {
    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      BlobInputStream bis = new BlobInputStream(blob, maxBufferSize);
      // Repeat: the size carries over between calls, so an overflow only shows up several calls
      // later. The collapse this catches happens on call 16 with an unbounded ceiling
      int previous = 0;
      for (int call = 0; call < 40; call++) {
        int size = bis.getNextBufferSize(len);
        int before = previous;
        assertTrue(size >= 1 && size <= maxBufferSize,
            () -> "buffer size " + size + " outside [1, " + maxBufferSize + "]");
        assertTrue(size >= before,
            () -> "buffer size fell from " + before + " to " + size);
        previous = size;
      }
    }
  }
}
