/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.postgresql.PGConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.fastpath.Fastpath;
import org.postgresql.test.TestUtil;
import org.postgresql.util.ByteStreamWriter;
import org.postgresql.util.GT;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * A write that {@link BlobOutputStream} sends because its buffer is full ends at a large object
 * offset that is a multiple of its alignment, wherever the stream started and after any
 * {@link BlobOutputStream#flush()}.
 *
 * <p>The alignment is 8192 for a buffer of at least 8192 bytes, 2048 for a buffer of at least
 * 2048, and none below that. The stream reads the offset with {@code lo_tell} once, moves it by
 * the bytes it sends, and reads it again after a flush. Each test of where writes end lists the
 * large object offset every write ended at, read back from the server, so a failure prints the
 * whole sequence.</p>
 *
 * <p>Every large object is created in a transaction the test never commits, so closing the
 * connection discards it whether the test passes or fails.</p>
 */
class BlobOutputStreamAlignmentTest {

  static Stream<Arguments> startOffsets() {
    return Stream.of(
        argumentSet("start 0, on a row boundary", 0L, Arrays.asList(73 * 8192L)),
        argumentSet("start 100, past a row boundary", 100L, Arrays.asList(73 * 8192L)),
        argumentSet("start 8191, just before a row boundary", 8191L, Arrays.asList(74 * 8192L)),
        argumentSet("start 8192, on the next row boundary", 8192L, Arrays.asList(74 * 8192L)),
        argumentSet("start 2147483748, past the 32-bit offset range", 2147483748L,
            Arrays.asList(2147483648L + 73 * 8192L)));
  }

  /**
   * {@link java.sql.Blob#setBinaryStream(long)} seeks before it returns a stream with the default
   * buffer, so the stream may start anywhere. Each case writes 600000 bytes, more than the buffer
   * holds, and the write the stream sends ends on the last row boundary the data reaches. A start
   * of 2147483748 is beyond what {@code lo_tell} can return, so only {@code lo_tell64} reads it,
   * and the case is skipped on servers before 9.3, which lack it.
   */
  @ParameterizedTest
  @MethodSource("startOffsets")
  void aWriteEndsOnARowBoundaryWhereverTheStreamStarts(long startOffset,
      List<Long> expectedWriteEnds) throws Exception {
    try (Connection con = openTransaction()) {
      assumeTrue(startOffset <= Integer.MAX_VALUE
              || TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_3),
          "lo_lseek64 and lo_tell64 need PostgreSQL 9.3");
      try (RecordingLargeObject lo = RecordingLargeObject.openAt(con, startOffset);
           BlobOutputStream os = new BlobOutputStream(lo)) {

        os.write(new byte[600000], 0, 600000);

        assertEquals(expectedWriteEnds, lo.writeEnds, "large object offsets the writes ended at");
      }
    }
  }

  static Stream<Arguments> bufferSizes() {
    return Stream.of(
        argumentSet("2047 rounds down to a 1024 buffer: no alignment", 2047,
            Arrays.asList(30000L)),
        argumentSet("2048: aligns on 2048", 2048, Arrays.asList(14 * 2048L)),
        argumentSet("8191 rounds down to a 4096 buffer: aligns on 2048", 8191,
            Arrays.asList(14 * 2048L)),
        argumentSet("8192: aligns on 8192", 8192, Arrays.asList(3 * 8192L)));
  }

  /**
   * Each case writes 30000 bytes from offset 0, which ends past a multiple of either alignment.
   * Starting at 0, the cases check only how the buffer size selects the alignment.
   */
  @ParameterizedTest
  @MethodSource("bufferSizes")
  void theAlignmentFollowsTheBufferSize(int bufferSize, List<Long> expectedWriteEnds)
      throws Exception {
    try (Connection con = openTransaction();
         RecordingLargeObject lo = RecordingLargeObject.openAt(con, 0);
         BlobOutputStream os = new BlobOutputStream(lo, bufferSize)) {

      os.write(new byte[30000], 0, 30000);

      assertEquals(expectedWriteEnds, lo.writeEnds, "large object offsets the writes ended at");
    }
  }

  /**
   * A stream that does not align sends everything it holds, so it never reads the offset, on
   * either the array path or the single-byte path.
   */
  @Test
  void aStreamThatDoesNotAlignNeverReadsTheOffset() throws Exception {
    try (Connection con = openTransaction();
         RecordingLargeObject lo = RecordingLargeObject.openAt(con, 100);
         BlobOutputStream os = new BlobOutputStream(lo, 1024)) {

      os.write(new byte[30000], 0, 30000);
      for (int i = 0; i < 3000; i++) {
        os.write(i);
      }
      os.flush();

      assertAll(
          () -> assertEquals(0, lo.offsetQueries, "lo_tell calls"),
          () -> assertEquals(Arrays.asList(30100L, 31124L, 32148L, 33100L), lo.writeEnds,
              "large object offsets the writes ended at"));
    }
  }

  /**
   * The second write fills the buffer, and the remainder past the row boundary at 8192 is longer
   * than that write, so the stream sends 8092 bytes from the buffer alone and keeps the rest.
   */
  @Test
  void aWriteSentFromTheBufferAloneEndsOnARowBoundary() throws Exception {
    try (Connection con = openTransaction();
         RecordingLargeObject lo = RecordingLargeObject.openAt(con, 100);
         BlobOutputStream os = new BlobOutputStream(lo, 8192)) {
      os.write(new byte[8150], 0, 8150);

      os.write(new byte[100], 0, 100);

      assertEquals(Arrays.asList(8192L), lo.writeEnds,
          "large object offsets the writes ended at");
    }
  }

  /**
   * The first write stays in the buffer, so the flush sends 100000 bytes and leaves the large
   * object 1696 bytes past a row boundary. The next write ends on a row boundary all the same.
   */
  @Test
  void aWriteAfterAFlushOfAnUnalignedAmountEndsOnARowBoundary() throws Exception {
    try (Connection con = openTransaction();
         RecordingLargeObject lo = RecordingLargeObject.openAt(con, 0);
         BlobOutputStream os = new BlobOutputStream(lo)) {
      os.write(new byte[100000], 0, 100000);
      os.flush();

      os.write(new byte[600000], 0, 600000);

      assertEquals(Arrays.asList(100000L, 85 * 8192L), lo.writeEnds,
          "large object offsets the writes ended at");
    }
  }

  /**
   * The first write makes the stream read the offset, and the flush then sends a remainder of
   * 3616 bytes. The small write that follows refills the buffer without reaching the server, and the
   * offset the next write ends on still accounts for the flushed bytes.
   */
  @Test
  void aWriteAfterAFlushAndASmallWriteEndsOnARowBoundary() throws Exception {
    try (Connection con = openTransaction();
         RecordingLargeObject lo = RecordingLargeObject.openAt(con, 0);
         BlobOutputStream os = new BlobOutputStream(lo, 8192)) {
      os.write(new byte[20000], 0, 20000);
      os.flush();
      os.write(new byte[100], 0, 100);

      os.write(new byte[20000], 0, 20000);

      assertEquals(Arrays.asList(2 * 8192L, 20000L, 4 * 8192L), lo.writeEnds,
          "large object offsets the writes ended at");
    }
  }

  /**
   * A caller may seek the large object once the stream is flushed, and the next write ends on a
   * row boundary counted from the offset it sought to.
   */
  @Test
  void aWriteAfterAFlushAndASeekEndsOnARowBoundaryFromTheNewOffset() throws Exception {
    try (Connection con = openTransaction();
         RecordingLargeObject lo = RecordingLargeObject.openAt(con, 0);
         BlobOutputStream os = new BlobOutputStream(lo)) {
      os.write(new byte[600000], 0, 600000);
      os.flush();
      lo.seek(123);

      os.write(new byte[600000], 0, 600000);

      assertEquals(Arrays.asList(73 * 8192L, 600000L, 73 * 8192L), lo.writeEnds,
          "large object offsets the writes ended at");
    }
  }

  /**
   * After the first write the buffer holds a remainder, so each later write sends bytes from both
   * the buffer and the caller's array. Every one ends on a row boundary, and only the first reads
   * the offset.
   */
  @Test
  void theOffsetIsReadOnceAndCarriedAcrossWrites() throws Exception {
    try (Connection con = openTransaction();
         RecordingLargeObject lo = RecordingLargeObject.openAt(con, 100);
         BlobOutputStream os = new BlobOutputStream(lo)) {

      os.write(new byte[600000], 0, 600000);
      os.write(new byte[600000], 0, 600000);
      os.write(new byte[600000], 0, 600000);

      assertAll(
          () -> assertEquals(Arrays.asList(73 * 8192L, 146 * 8192L, 219 * 8192L), lo.writeEnds,
              "large object offsets the writes ended at"),
          () -> assertEquals(1, lo.offsetQueries, "lo_tell calls"));
    }
  }

  static Stream<Arguments> wholeRowChunks() {
    return Stream.of(
        argumentSet("256 chunks of 8192 bytes, filling the buffer", 8192, 256),
        argumentSet("4 chunks of 524288 bytes, each a whole buffer", 524288, 4));
  }

  /**
   * Each case writes 2097152 bytes from offset 0 in chunks of whole rows, so every write the
   * stream sends goes out whole and leaves the buffer empty. 8192 bytes is the largest chunk the
   * driver's own blob path writes. Four writes cost one round trip for the offset, not one per
   * write.
   */
  @ParameterizedTest
  @MethodSource("wholeRowChunks")
  void aSteadyWriterOfWholeRowsReadsTheOffsetOnce(int chunkSize, int chunks)
      throws Exception {
    try (Connection con = openTransaction();
         RecordingLargeObject lo = RecordingLargeObject.openAt(con, 0);
         BlobOutputStream os = new BlobOutputStream(lo)) {
      byte[] chunk = new byte[chunkSize];

      for (int i = 0; i < chunks; i++) {
        os.write(chunk, 0, chunk.length);
      }

      assertAll(
          () -> assertEquals(1, lo.offsetQueries, "lo_tell calls"),
          () -> assertEquals(
              Arrays.asList(64 * 8192L, 128 * 8192L, 192 * 8192L, 256 * 8192L), lo.writeEnds,
              "large object offsets the writes ended at"));
    }
  }

  static Stream<Arguments> singleByteStartOffsets() {
    return Stream.of(
        argumentSet("start 0, on a row boundary", 0L),
        argumentSet("start 100, past a row boundary", 100L));
  }

  /**
   * Each case writes 20000 single bytes into an 8192-byte buffer. From 100, the first full buffer
   * goes out without its last 100 bytes, and the second one ends on a row boundary only if the
   * stream moved the offset by the 8092 bytes the first one sent. From 0, every buffer goes out
   * whole.
   */
  @ParameterizedTest
  @MethodSource("singleByteStartOffsets")
  void singleByteWritesEndOnARowBoundaryWhereverTheStreamStarts(long startOffset)
      throws Exception {
    try (Connection con = openTransaction();
         RecordingLargeObject lo = RecordingLargeObject.openAt(con, startOffset);
         BlobOutputStream os = new BlobOutputStream(lo, 8192)) {

      for (int i = 0; i < 20000; i++) {
        os.write(i);
      }

      assertEquals(Arrays.asList(8192L, 2 * 8192L), lo.writeEnds,
          "large object offsets the writes ended at");
    }
  }

  /**
   * A write that needs the offset fails when reading it fails, and sends nothing.
   */
  @Test
  void aFailureToReadTheOffsetFailsTheWrite() throws Exception {
    try (Connection con = openTransaction();
         RecordingLargeObject lo = RecordingLargeObject.openAt(con, 0);
         BlobOutputStream os = new BlobOutputStream(lo, 8192)) {
      SQLException tellFailure = new SQLException("lo_tell failed");
      lo.tellFailure = tellFailure;

      IOException e = assertThrows(IOException.class, () -> os.write(new byte[20000], 0, 20000));

      assertAll(
          () -> assertSame(tellFailure, e.getCause(), "cause"),
          () -> assertEquals(
              GT.tr("Can not write data to large object {0}, requested write length: {1}",
                  lo.getLongOID(), 20000),
              e.getMessage(), "message"),
          () -> assertEquals(Arrays.asList(), lo.writeEnds,
              "large object offsets the writes ended at"));
    }
  }

  /**
   * The sequence holds a remainder back on each path that keeps one: from the buffer alone, from
   * the caller's array after the whole buffer, and on the single-byte path. Each segment has its
   * own content, so a byte moved, lost, or written twice shows in the comparison.
   */
  @Test
  void realigningKeepsEveryByteInPlace() throws Exception {
    byte[] prefix = bytes(4000, 1);
    byte[] buffered = bytes(7000, 2);
    byte[] splitByTheRemainder = bytes(1500, 3);
    byte[] singleBytes = bytes(9000, 4);
    byte[] sentFromBufferAndArray = bytes(20000, 5);
    try (Connection con = openTransaction()) {
      LargeObjectManager lom = con.unwrap(PGConnection.class).getLargeObjectAPI();
      long oid = lom.createLO();
      try (LargeObject lo = lom.open(oid);
           BlobOutputStream os = new BlobOutputStream(lo, 8192)) {
        lo.write(prefix);
        os.write(buffered, 0, buffered.length);
        os.write(splitByTheRemainder, 0, splitByTheRemainder.length);
        os.flush();
        for (byte b : singleBytes) {
          os.write(b);
        }
        os.write(sentFromBufferAndArray, 0, sentFromBufferAndArray.length);
      }

      try (LargeObject lo = lom.open(oid, LargeObjectManager.READ)) {
        assertArrayEquals(
            concat(prefix, buffered, splitByTheRemainder, singleBytes, sentFromBufferAndArray),
            lo.read(lo.size()), "large object contents");
      }
    }
  }

  private static Connection openTransaction() throws SQLException {
    Connection con = TestUtil.openDB();
    con.setAutoCommit(false);
    return con;
  }

  private static byte[] bytes(int length, int seed) {
    byte[] bytes = new byte[length];
    new Random(seed).nextBytes(bytes);
    return bytes;
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.write(part, 0, part.length);
    }
    return out.toByteArray();
  }

  /**
   * Records the large object offset each write ends at, as the server reports it, and counts the
   * {@code lo_tell} calls the stream makes. Setting {@link #tellFailure} makes every
   * {@code lo_tell} call the stream makes throw it.
   */
  private static final class RecordingLargeObject extends LargeObject {
    final List<Long> writeEnds = new ArrayList<>();
    int offsetQueries;
    @Nullable SQLException tellFailure;

    private RecordingLargeObject(Fastpath fp, long oid) throws SQLException {
      super(fp, oid, LargeObjectManager.READWRITE);
    }

    /**
     * Creates an empty large object and opens it positioned at {@code offset}. Nothing is stored
     * before the offset, so a start past 2 GiB costs no data.
     */
    static RecordingLargeObject openAt(Connection con, long offset) throws SQLException {
      PGConnection pgCon = con.unwrap(PGConnection.class);
      long oid = pgCon.getLargeObjectAPI().createLO();
      RecordingLargeObject lo = new RecordingLargeObject(pgCon.getFastpathAPI(), oid);
      if (offset <= Integer.MAX_VALUE) {
        lo.seek((int) offset);
      } else {
        lo.seek64(offset, SEEK_SET);
      }
      return lo;
    }

    @Override
    public int tell() throws SQLException {
      offsetQueries++;
      if (tellFailure != null) {
        throw tellFailure;
      }
      return super.tell();
    }

    @Override
    public long tell64() throws SQLException {
      offsetQueries++;
      if (tellFailure != null) {
        throw tellFailure;
      }
      return super.tell64();
    }

    @Override
    public void write(byte[] buf, int off, int len) throws SQLException {
      super.write(buf, off, len);
      recordWriteEnd();
    }

    @Override
    public void write(ByteStreamWriter writer) throws SQLException {
      super.write(writer);
      recordWriteEnd();
    }

    private void recordWriteEnd() throws SQLException {
      writeEnds.add(supports64BitOffsets() ? super.tell64() : super.tell());
    }
  }
}
