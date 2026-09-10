/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.postgresql.copy.PGCopyOutputStream;
import org.postgresql.test.TestUtil;
import org.postgresql.util.ByteStreamWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fails when a COPY write reaches the server with a range that runs past the end of its array.
 *
 * <p>{@code PGStream.send} pads such a range with zero bytes rather than failing, so the bytes the
 * array does not have arrive as COPY data. {@code PGCopyOutputStream} decides between passing a
 * write through and buffering it by comparing it to its buffer, so which of the two happens - and
 * therefore whether the request was refused at all - depended on a size the caller did not choose
 * with that in mind.</p>
 */
class CopyWriteBoundsTest {
  private Connection con;
  private CopyManager copyAPI;

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createTempTable(con, "copybounds", "stringvalue text");
    copyAPI = con.unwrap(PGConnection.class).getCopyAPI();
  }

  @AfterEach
  void tearDown() throws Exception {
    TestUtil.closeDB(con);
  }

  private int rowCount() throws SQLException {
    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("select count(*) from copybounds")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /**
   * The buffer sizes straddle the branch: 64 sends the caller's array straight to the server, and
   * 8192 copies it into the internal buffer first.
   */
  @ParameterizedTest
  @ValueSource(ints = {64, 8192})
  void theStreamRejectsARangePastTheEndOfTheArray(int bufferSize) throws Exception {
    try (PGCopyOutputStream os =
             new PGCopyOutputStream(con.unwrap(PGConnection.class),
                 "COPY copybounds FROM STDIN", bufferSize)) {
      byte[] b = new byte[10];
      IndexOutOfBoundsException range =
          assertThrows(IndexOutOfBoundsException.class, () -> os.write(b, 5, 200));
      // Exactly IndexOutOfBoundsException, not the ArrayIndexOutOfBoundsException that
      // System.arraycopy throws: the stream has to refuse the range itself, before it decides
      // whether to buffer the write or pass it through, and before it flushes what it already has
      assertEquals(IndexOutOfBoundsException.class, range.getClass(),
          "the stream refused the range only after handing it on: " + range);
      assertThrows(IndexOutOfBoundsException.class, () -> os.write(b, -1, 1));
      assertThrows(IndexOutOfBoundsException.class, () -> os.write(b, 0, -1));
      assertThrows(IndexOutOfBoundsException.class, () -> os.write(b, 11, 0));
      // Partially overlapping: the range starts inside the array and runs off the end. A guard
      // that only compared the length to the whole array would let this one through
      assertThrows(IndexOutOfBoundsException.class, () -> os.write(b, 5, 8));
      assertThrows(IndexOutOfBoundsException.class,
          () -> os.write(b, 3, Integer.MAX_VALUE));
      NullPointerException e = assertThrows(NullPointerException.class, () -> os.write(null, 0, 5));
      assertNotNull(e.getMessage(), "the exception has to say which argument was null");

      // The stream is still usable, and an empty range at the very end of the array is accepted
      os.write(b, 10, 0);
      os.write("only row\n".getBytes("UTF-8"));
    }
    assertEquals(1, rowCount(), "only the row the caller actually wrote may arrive");
  }

  /**
   * {@link CopyIn#writeToCopy(byte[], int, int)} is public API of its own, and the implementation
   * the copy API hands out does not go through {@code PGCopyOutputStream}.
   */
  @Test
  void copyInRejectsARangePastTheEndOfTheArray() throws Exception {
    CopyIn cp = copyAPI.copyIn("COPY copybounds FROM STDIN");
    try {
      byte[] b = new byte[10];
      assertThrows(IndexOutOfBoundsException.class, () -> cp.writeToCopy(b, 5, 200));
      assertThrows(IndexOutOfBoundsException.class, () -> cp.writeToCopy(b, -1, 1));
      assertThrows(IndexOutOfBoundsException.class, () -> cp.writeToCopy(b, 0, -1));
      assertThrows(IndexOutOfBoundsException.class, () -> cp.writeToCopy(b, 5, 8));
      assertThrows(NullPointerException.class, () -> cp.writeToCopy(null, 0, 5));

      byte[] row = "only row\n".getBytes("UTF-8");
      cp.writeToCopy(row, 0, row.length);
      cp.endCopy();
    } finally {
      if (cp.isActive()) {
        cp.cancelCopy();
      }
    }
    assertEquals(1, rowCount(), "only the row the caller actually wrote may arrive");
  }

  /**
   * Records what the stream hands on, so a test can see whether a rejected write got that far.
   */
  private static class RecordingCopyIn implements CopyIn {
    private final List<Integer> handedOn = new ArrayList<>();

    @Override
    public void writeToCopy(byte[] buf, int off, int siz) {
      handedOn.add(siz);
    }

    @Override
    public void writeToCopy(ByteStreamWriter from) {
      handedOn.add(from.getLength());
    }

    @Override
    public void flushCopy() {
    }

    @Override
    public long endCopy() {
      return 0;
    }

    @Override
    public int getFieldCount() {
      return 1;
    }

    @Override
    public int getFormat() {
      return 0;
    }

    @Override
    public int getFieldFormat(int field) {
      return 0;
    }

    @Override
    public boolean isActive() {
      return true;
    }

    @Override
    public void cancelCopy() {
    }

    @Override
    public long getHandledRowCount() {
      return 0;
    }
  }

  /**
   * A rejected write must not send what the stream had already accepted. The stream flushes its
   * buffer whenever the incoming write would not fit in what is left of it, and a bad range takes
   * that branch as readily as a good one, so the check has to come first.
   */
  @Test
  void aRejectedWriteDoesNotFlushWhatWasAlreadyAccepted() throws Exception {
    RecordingCopyIn op = new RecordingCopyIn();
    try (PGCopyOutputStream os = new PGCopyOutputStream(op, 64)) {
      os.write(new byte[10], 0, 10);
      assertEquals(Collections.emptyList(), op.handedOn, "the accepted write should still be held");

      // 200 does not fit in what is left of the 64-byte buffer, which is the branch that flushes
      assertThrows(IndexOutOfBoundsException.class, () -> os.write(new byte[10], 5, 200));
      assertEquals(Collections.emptyList(), op.handedOn,
          "the rejected write flushed the bytes the stream had already accepted");

      // Same, for a range that overlaps the array only partly: it would reach arraycopy rather
      // than the connection, but only after the flush had already fired
      assertThrows(IndexOutOfBoundsException.class, () -> os.write(new byte[10], 5, 8));
      assertEquals(Collections.emptyList(), op.handedOn,
          "a partially overlapping range flushed the bytes the stream had already accepted");
    }
    assertEquals(Collections.singletonList(10), op.handedOn,
        "closing the stream should hand on exactly what was accepted");
  }

  /**
   * A window from the middle of the array has to arrive as that window, so that a check which
   * rejected too much would be caught rather than looking like success.
   */
  @Test
  void theStreamWritesTheWindowTheCallerAskedFor() throws Exception {
    byte[] source = "xxxxxfirst\nsecond\nyyyyy".getBytes("UTF-8");
    try (PGCopyOutputStream os =
             new PGCopyOutputStream(con.unwrap(PGConnection.class),
                 "COPY copybounds FROM STDIN", 64)) {
      os.write(source, 5, "first\nsecond\n".length());
    }
    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("select stringvalue from copybounds order by stringvalue")) {
      rs.next();
      assertEquals("first", rs.getString(1));
      rs.next();
      assertEquals("second", rs.getString(1));
    }
  }
}
