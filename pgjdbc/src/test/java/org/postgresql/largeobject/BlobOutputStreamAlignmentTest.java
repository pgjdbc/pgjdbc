/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGConnection;
import org.postgresql.fastpath.Fastpath;
import org.postgresql.test.TestUtil;
import org.postgresql.util.ByteStreamWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * PostgreSQL stores a large object in rows of {@code LOBLKSIZE} bytes. A write that ends inside a
 * row makes the server read that row back and update it, so the stream holds the remainder back
 * and ends its own writes on a row boundary. The boundary is one of the large object, not of the
 * byte count this stream happens to have issued, and the two differ as soon as the stream starts
 * anywhere but zero or the caller flushes an amount of its own choosing.
 */
class BlobOutputStreamAlignmentTest {
  private static final int ALIGNMENT = 8192;

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
   * Records the large object offset each write ends at, which is what the alignment is about.
   */
  private static class RecordingLargeObject extends LargeObject {
    private final List<Long> writeEnds = new ArrayList<>();
    private long offset;
    private int tells;

    RecordingLargeObject(Fastpath fp, long oid, int mode) throws SQLException {
      super(fp, oid, mode);
    }

    void startAt(int offset) throws SQLException {
      seek(offset);
      this.offset = offset;
    }

    @Override
    public int tell() throws SQLException {
      tells++;
      return super.tell();
    }

    @Override
    public long tell64() throws SQLException {
      tells++;
      return super.tell64();
    }

    private void record(int len) {
      offset += len;
      writeEnds.add(offset);
    }

    @Override
    public void write(byte[] buf) throws SQLException {
      record(buf.length);
      super.write(buf);
    }

    @Override
    public void write(byte[] buf, int off, int len) throws SQLException {
      record(len);
      super.write(buf, off, len);
    }

    @Override
    public void write(ByteStreamWriter writer) throws SQLException {
      record(writer.getLength());
      super.write(writer);
    }
  }

  /**
   * A stream handed out by {@link java.sql.Blob#setBinaryStream(long)} starts wherever the caller
   * asked, so counting from zero aligns to the wrong boundary for the whole life of the stream.
   */
  @Test
  void writesAlignFromAnUnalignedStart() throws Exception {
    long oid = lom.createLO();
    try (LargeObject seed = lom.open(oid)) {
      seed.write(payload(100));
    }
    RecordingLargeObject lo = new RecordingLargeObject(fastpath, oid, LargeObjectManager.READWRITE);
    lo.startAt(100);
    BlobOutputStream os = new BlobOutputStream(lo, 512 * 1024);
    os.write(payload(600000), 0, 600000);

    assertAlignedFrom(lo.writeEnds, 0);

    os.flush();
    lo.close();
    lom.delete(oid);
  }

  /**
   * An explicit flush writes an amount only the caller knows, so everything after it is off the
   * boundary unless the stream tracks where it actually is.
   */
  @Test
  void writesRealignAfterAnExplicitFlush() throws Exception {
    long oid = lom.createLO();
    RecordingLargeObject lo = new RecordingLargeObject(fastpath, oid, LargeObjectManager.READWRITE);
    lo.startAt(0);
    BlobOutputStream os = new BlobOutputStream(lo, 512 * 1024);

    os.write(payload(100000), 0, 100000);
    int flushIndex = lo.writeEnds.size();
    os.flush();
    assertEquals(flushIndex + 1, lo.writeEnds.size(), "the flush wrote nothing");
    assertTrue(lo.writeEnds.get(flushIndex) % ALIGNMENT != 0,
        "the test needs the flush to land off a row boundary, it ended at "
            + lo.writeEnds.get(flushIndex));

    os.write(payload(600000), 0, 600000);
    assertAlignedFrom(lo.writeEnds, flushIndex + 1);

    os.flush();
    lo.close();
    lom.delete(oid);
  }

  /**
   * The second large write has to align against where the first one left the large object, which
   * means carrying the offset forward rather than asking again. Asking again would also be
   * correct, so the round trip count is asserted as well: it is the reason for carrying it.
   */
  @Test
  void theOffsetIsCarriedFromOneWriteToTheNext() throws Exception {
    long oid = lom.createLO();
    try (LargeObject seed = lom.open(oid)) {
      seed.write(payload(100));
    }
    RecordingLargeObject lo = new RecordingLargeObject(fastpath, oid, LargeObjectManager.READWRITE);
    lo.startAt(100);
    BlobOutputStream os = new BlobOutputStream(lo, 512 * 1024);
    os.write(payload(600000), 0, 600000);
    os.write(payload(600001), 0, 600001);

    assertAlignedFrom(lo.writeEnds, 0);
    assertEquals(2, lo.writeEnds.size(), "the second write did not reach the server");
    assertEquals(1, lo.tells, "the offset was asked for more than once");

    os.flush();
    lo.close();
    lom.delete(oid);
  }

  /**
   * A flush empties the buffer, and with nothing buffered a seek is unambiguous. The offset the
   * stream carried is then the server's old one, so it has to be asked for again.
   */
  @Test
  void theOffsetIsAskedForAgainAfterAFlushAndSeek() throws Exception {
    long oid = lom.createLO();
    try (LargeObject seed = lom.open(oid)) {
      seed.write(payload(700000));
    }
    RecordingLargeObject lo = new RecordingLargeObject(fastpath, oid, LargeObjectManager.READWRITE);
    lo.startAt(0);
    BlobOutputStream os = new BlobOutputStream(lo, 512 * 1024);
    os.write(payload(600000), 0, 600000);
    os.flush();

    lo.startAt(123);
    int afterSeek = lo.writeEnds.size();
    os.write(payload(600000), 0, 600000);
    assertAlignedFrom(lo.writeEnds, afterSeek);

    os.flush();
    lo.close();
    lom.delete(oid);
  }

  /**
   * A single-byte write flushes a whole buffer without going through the write plan, so the offset
   * has to move with it.
   *
   * <p>This is asserted on the field rather than through an aligned write, and it has to be: the
   * buffer size is always a multiple of the alignment, so an offset left behind by exactly one
   * buffer is congruent to the right one and every alignment assertion would pass either way. The
   * field is what the next write plan reads, so the field is what this checks.</p>
   */
  @Test
  void aSingleByteFlushMovesTheOffsetWithIt() throws Exception {
    long oid = lom.createLO();
    try (LargeObject seed = lom.open(oid)) {
      seed.write(payload(100));
    }
    RecordingLargeObject lo = new RecordingLargeObject(fastpath, oid, LargeObjectManager.READWRITE);
    lo.startAt(100);
    BlobOutputStream os = new BlobOutputStream(lo, 8192);

    os.write(payload(8192), 0, 8192);
    // 100 of the 8192 bytes are held back to land the write on the 8192 boundary
    assertEquals(8192L, os.writePosition, "offset after the first plan-driven write");

    for (int i = 0; i < 8193; i++) {
      os.write(i);
    }
    assertEquals(1, lo.writeEnds.size() - 1, "the single-byte writes flushed more than once");
    assertEquals(8192L + 8192, os.writePosition,
        "the offset did not move with the buffer the single-byte writes flushed");

    os.flush();
    lo.close();
    lom.delete(oid);
  }

  /**
   * A flush moves the server by an amount only the flush knows, and it does not have to be the
   * last thing the stream does. Refilling the buffer afterwards must not put the pre-flush offset
   * back into service. No seek is involved here: the skew comes from the flush alone.
   */
  @Test
  void aFlushFollowedByASmallWriteDoesNotRestoreTheOldOffset() throws Exception {
    long oid = lom.createLO();
    RecordingLargeObject lo = new RecordingLargeObject(fastpath, oid, LargeObjectManager.READWRITE);
    lo.startAt(0);
    BlobOutputStream os = new BlobOutputStream(lo, 8192);

    os.write(payload(20000), 0, 20000);
    os.flush();
    os.write(payload(100), 0, 100);
    int afterRefill = lo.writeEnds.size();
    os.write(payload(20000), 0, 20000);

    assertAlignedFrom(lo.writeEnds, afterRefill);

    os.flush();
    lo.close();
    lom.delete(oid);
  }

  /**
   * A stream fed one byte at a time flushes whole buffers, which keeps an alignment it already has
   * but cannot establish one. A stream handed out by {@link java.sql.Blob#setBinaryStream(long)}
   * does not start with one.
   */
  @Test
  void singleByteWritesAlignFromAnUnalignedStart() throws Exception {
    long oid = lom.createLO();
    try (LargeObject seed = lom.open(oid)) {
      seed.write(payload(100));
    }
    RecordingLargeObject lo = new RecordingLargeObject(fastpath, oid, LargeObjectManager.READWRITE);
    lo.startAt(100);
    BlobOutputStream os = new BlobOutputStream(lo, 8192);

    for (int i = 0; i < 20000; i++) {
      os.write(i);
    }

    assertAlignedFrom(lo.writeEnds, 0);

    os.flush();
    lo.close();
    lom.delete(oid);
  }

  /**
   * The driver's own blob path feeds fixed 8KiB chunks into a 512KiB buffer, so every cycle comes
   * out exactly aligned and leaves nothing buffered. That path opens the large object at zero and
   * never flushes, so it never had the misalignment this change fixes, and it must not start
   * paying a round trip per buffer for the fix either.
   */
  @Test
  void aSteadyWriterAsksForTheOffsetOnce() throws Exception {
    long oid = lom.createLO();
    RecordingLargeObject lo = new RecordingLargeObject(fastpath, oid, LargeObjectManager.READWRITE);
    lo.startAt(0);
    BlobOutputStream os = new BlobOutputStream(lo, 512 * 1024);

    byte[] chunk = payload(8192);
    for (int i = 0; i < 256; i++) {
      os.write(chunk, 0, chunk.length);
    }
    os.flush();

    assertEquals(1, lo.tells, "the offset was asked for once per buffer instead of once");
    assertAlignedFrom(lo.writeEnds, 0);

    lo.close();
    lom.delete(oid);
  }

  /**
   * A buffer smaller than a large object row cannot carry a remainder, so there is nothing to
   * align and no reason to spend a round trip finding out where the stream is.
   */
  @Test
  void aBufferTooSmallToAlignNeverAsksForTheOffset() throws Exception {
    long oid = lom.createLO();
    RecordingLargeObject lo = new RecordingLargeObject(fastpath, oid, LargeObjectManager.READWRITE);
    lo.startAt(0);
    BlobOutputStream os = new BlobOutputStream(lo, 1024);
    os.write(payload(30000), 0, 30000);
    os.flush();

    assertEquals(0, lo.tells, "a stream with nothing to align asked the server where it is");
    assertFalse(lo.writeEnds.isEmpty(), "the stream never reached the server");

    lo.close();
    lom.delete(oid);
  }

  /**
   * A buffer between one row and the larger alignment aims at 2KiB, the default {@code LOBLKSIZE}.
   */
  @Test
  void aMediumBufferAlignsOnTwoKibibytes() throws Exception {
    long oid = lom.createLO();
    try (LargeObject seed = lom.open(oid)) {
      seed.write(payload(100));
    }
    RecordingLargeObject lo = new RecordingLargeObject(fastpath, oid, LargeObjectManager.READWRITE);
    lo.startAt(100);
    BlobOutputStream os = new BlobOutputStream(lo, 4096);
    os.write(payload(30000), 0, 30000);
    os.write(payload(30001), 0, 30001);
    os.write(payload(30002), 0, 30002);

    assertEquals(3, lo.writeEnds.size(), "one write per call was expected");
    for (int i = 0; i < lo.writeEnds.size(); i++) {
      assertEquals(0, lo.writeEnds.get(i) % 2048,
          "write " + i + " ended at large object offset " + lo.writeEnds.get(i)
              + ", which is " + lo.writeEnds.get(i) % 2048 + " past a 2KiB row boundary");
    }

    os.flush();
    lo.close();
    lom.delete(oid);
  }

  /**
   * Realigning must not cost or duplicate a byte.
   */
  @Test
  void realigningPreservesTheBytes() throws Exception {
    long oid = lom.createLO();
    byte[] first = payload(100000);
    byte[] second = payload(600000);
    try (LargeObject lo = lom.open(oid)) {
      BlobOutputStream os = new BlobOutputStream(lo, 512 * 1024);
      os.write(first, 0, first.length);
      os.flush();
      os.write(second, 0, second.length);
      os.flush();
      lo.seek(0);
      byte[] expected = new byte[first.length + second.length];
      System.arraycopy(first, 0, expected, 0, first.length);
      System.arraycopy(second, 0, expected, first.length, second.length);
      assertArrayEquals(expected, lo.read(lo.size()), "large object contents");
    }
    lom.delete(oid);
  }

  private static void assertAlignedFrom(List<Long> writeEnds, int from) {
    int checked = 0;
    for (int i = from; i < writeEnds.size(); i++) {
      assertEquals(0, writeEnds.get(i) % ALIGNMENT,
          "write " + i + " ended at large object offset " + writeEnds.get(i) + ", which is "
              + writeEnds.get(i) % ALIGNMENT + " past a row boundary");
      checked++;
    }
    assertTrue(checked > 0, "no write to check: the stream never flushed on its own");
  }

  private static byte[] payload(int length) {
    byte[] payload = new byte[length];
    new Random(length * 6364136223846793005L + 1442695040888963407L).nextBytes(payload);
    return payload;
  }
}
