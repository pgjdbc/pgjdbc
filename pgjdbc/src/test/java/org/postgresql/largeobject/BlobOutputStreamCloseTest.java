/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.fastpath.Fastpath;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link BlobOutputStream#close()} calls {@link LargeObject#close()} once and leaves the stream
 * closed, whichever of its two steps fails: the flush of the buffered bytes, or the release of the
 * descriptor.
 *
 * <p>When both fail, the flush failure is the exception the caller sees, and the release failure
 * is suppressed onto it. Every test buffers three bytes, fewer than the stream's buffer holds, so
 * the first write to the large object happens inside {@code close()}.</p>
 */
class BlobOutputStreamCloseTest {
  private static final String WRITE_REFUSED = "write refused by the test";
  private static final String RELEASE_REFUSED = "release refused by the test";
  private static final byte[] PAYLOAD = {1, 2, 3};

  private Connection con;
  private LargeObjectManager lom;
  private Fastpath fastpath;

  /** Which of the two steps of {@code BlobOutputStream.close()} the large object refuses. */
  enum Failure {
    NONE(false, false),
    FLUSH(true, false),
    RELEASE(false, true),
    FLUSH_AND_RELEASE(true, true);

    final boolean failWrite;
    final boolean failRelease;

    Failure(boolean failWrite, boolean failRelease) {
      this.failWrite = failWrite;
      this.failRelease = failRelease;
    }
  }

  /**
   * A large object that refuses its writes, its release, or both, as the given {@link Failure}
   * selects, and counts the attempts at each. A refused release leaves the server-side descriptor
   * open; the rollback in {@link #tearDown()} closes it.
   */
  private static class FailingLargeObject extends LargeObject {
    private final Failure failure;
    int writeCount;
    int closeCount;

    FailingLargeObject(Fastpath fp, long oid, Failure failure, @Nullable BaseConnection conn,
        boolean commitOnClose) throws SQLException {
      super(fp, oid, LargeObjectManager.READWRITE, conn, commitOnClose);
      this.failure = failure;
    }

    @Override
    public void write(byte[] buf, int off, int len) throws SQLException {
      writeCount++;
      if (failure.failWrite) {
        throw new PSQLException(WRITE_REFUSED, PSQLState.IO_ERROR);
      }
      super.write(buf, off, len);
    }

    @Override
    public void close() throws SQLException {
      closeCount++;
      if (failure.failRelease) {
        throw new PSQLException(RELEASE_REFUSED, PSQLState.IO_ERROR);
      }
      super.close();
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    con.setAutoCommit(false);
    PGConnection pgCon = con.unwrap(PGConnection.class);
    lom = pgCon.getLargeObjectAPI();
    fastpath = pgCon.getFastpathAPI();
  }

  /**
   * Rolls back the transaction, which deletes every large object a test created and closes every
   * descriptor a test left open, whether or not the test passed.
   */
  @AfterEach
  void tearDown() throws SQLException {
    if (con == null) {
      return;
    }
    try {
      con.rollback();
    } finally {
      TestUtil.closeDB(con);
    }
  }

  private FailingLargeObject createLargeObject(Failure failure) throws SQLException {
    return new FailingLargeObject(fastpath, lom.createLO(), failure, null, false);
  }

  /** Returns a stream over {@code lo} that holds {@link #PAYLOAD} in its buffer. */
  private static BlobOutputStream streamWithBufferedPayload(LargeObject lo) throws IOException {
    BlobOutputStream os = new BlobOutputStream(lo, 1024);
    os.write(PAYLOAD, 0, PAYLOAD.length);
    return os;
  }

  private byte[] contentsOf(long oid) throws SQLException {
    try (LargeObject lo = lom.open(oid)) {
      return lo.read(lo.size());
    }
  }

  private static @Nullable String messageOf(@Nullable Throwable t) {
    return t == null ? null : t.getMessage();
  }

  private static List<@Nullable String> messagesOf(Throwable[] throwables) {
    List<@Nullable String> messages = new ArrayList<>();
    for (Throwable t : throwables) {
      messages.add(t.getMessage());
    }
    return messages;
  }

  private static List<@Nullable String> sqlStatesOf(Throwable[] throwables) {
    List<@Nullable String> sqlStates = new ArrayList<>();
    for (Throwable t : throwables) {
      sqlStates.add(t instanceof SQLException ? ((SQLException) t).getSQLState() : t.toString());
    }
    return sqlStates;
  }

  private boolean largeObjectExists(long oid) throws SQLException {
    try (PreparedStatement ps =
             con.prepareStatement("select 1 from pg_largeobject_metadata where oid = ?")) {
      ps.setLong(1, oid);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /**
   * Closes the stream and drops whatever it throws. The tests that call this assert a consequence
   * of the first close other than the exception it throws, which is asserted by
   * {@link #aFailedCloseThrowsTheFirstFailureAndCallsLargeObjectCloseOnce}.
   */
  private static void closeIgnoringFailure(OutputStream os) {
    try {
      os.close();
    } catch (IOException ignored) {
      // See the method comment
    }
  }

  @Test
  void closeWritesTheBufferedBytesAndReleasesTheDescriptor() throws Exception {
    FailingLargeObject lo = createLargeObject(Failure.NONE);
    BlobOutputStream os = streamWithBufferedPayload(lo);

    os.close();

    assertAll(
        () -> assertArrayEquals(PAYLOAD, contentsOf(lo.getLongOID()),
            "large object contents after BlobOutputStream.close()"),
        () -> assertEquals(1, lo.closeCount, "LargeObject.close() calls"));
  }

  static Stream<Arguments> failedCloses() {
    return Stream.of(
        arguments(Failure.FLUSH, WRITE_REFUSED, Collections.emptyList()),
        arguments(Failure.RELEASE, RELEASE_REFUSED, Collections.emptyList()),
        arguments(Failure.FLUSH_AND_RELEASE, WRITE_REFUSED, Arrays.asList(RELEASE_REFUSED)));
  }

  /**
   * {@link LargeObject#close()} is called even when the flush fails, and the first failure is the
   * exception thrown. A failed flush used to skip {@link LargeObject#close()}, so the descriptor
   * stayed open until the transaction ended.
   */
  @ParameterizedTest
  @MethodSource("failedCloses")
  void aFailedCloseThrowsTheFirstFailureAndCallsLargeObjectCloseOnce(
      Failure failure, String expectedCause, List<String> expectedSuppressed) throws Exception {
    FailingLargeObject lo = createLargeObject(failure);
    BlobOutputStream os = streamWithBufferedPayload(lo);

    IOException e = assertThrows(IOException.class, os::close);

    assertAll(
        () -> assertEquals(expectedCause, messageOf(e.getCause()),
            "cause of the BlobOutputStream.close() failure"),
        () -> assertEquals(expectedSuppressed, messagesOf(e.getSuppressed()),
            "failures suppressed onto the BlobOutputStream.close() failure"),
        () -> assertEquals(1, lo.closeCount, "LargeObject.close() calls"));
  }

  /**
   * A failed first close used to leave the stream open: a second close retried the flush that had
   * already failed, or called {@link LargeObject#close()} a second time.
   */
  @ParameterizedTest
  @EnumSource(Failure.class)
  void aSecondCloseDoesNothing(Failure failure) throws Exception {
    FailingLargeObject lo = createLargeObject(failure);
    BlobOutputStream os = streamWithBufferedPayload(lo);
    closeIgnoringFailure(os);
    int writesInFirstClose = lo.writeCount;

    assertDoesNotThrow(os::close, "second BlobOutputStream.close()");

    assertAll(
        () -> assertEquals(writesInFirstClose, lo.writeCount,
            "LargeObject.write() calls after the first close"),
        () -> assertEquals(1, lo.closeCount, "LargeObject.close() calls"));
  }

  /**
   * A failed close used to leave the stream accepting writes to a large object whose descriptor
   * the caller believed was released.
   */
  @ParameterizedTest
  @EnumSource(Failure.class)
  void aWriteAfterCloseIsRefused(Failure failure) throws Exception {
    FailingLargeObject lo = createLargeObject(failure);
    BlobOutputStream os = streamWithBufferedPayload(lo);
    closeIgnoringFailure(os);

    assertThrows(IOException.class, () -> os.write(new byte[]{4}, 0, 1),
        "BlobOutputStream.write(byte[1], 0, 1) after close()");
  }

  /**
   * {@link LargeObject#close()} flushes the stream {@link LargeObject#getOutputStream()} returned,
   * so closing that stream calls back into it while the release is in progress. The bytes are
   * written once, and the flush in that callback passes the stream's closed check.
   */
  @Test
  void closingTheStreamFromGetOutputStreamWritesTheBytesOnce() throws Exception {
    FailingLargeObject lo = createLargeObject(Failure.NONE);
    OutputStream os = lo.getOutputStream();
    os.write(PAYLOAD, 0, PAYLOAD.length);

    os.close();

    assertAll(
        () -> assertArrayEquals(PAYLOAD, contentsOf(lo.getLongOID()),
            "large object contents after closing LargeObject.getOutputStream()"),
        () -> assertEquals(1, lo.closeCount, "LargeObject.close() calls"));
  }

  /**
   * The bytes a failed flush left in the buffer are dropped before the release, so the flush that
   * {@link LargeObject#close()} makes on the stream from {@link LargeObject#getOutputStream()}
   * has nothing to write.
   */
  @Test
  void aFailedFlushOfTheStreamFromGetOutputStreamIsNotRetriedByTheRelease() throws Exception {
    FailingLargeObject lo = createLargeObject(Failure.FLUSH);
    OutputStream os = lo.getOutputStream();
    os.write(PAYLOAD, 0, PAYLOAD.length);

    IOException e = assertThrows(IOException.class, os::close);

    assertAll(
        () -> assertEquals(1, lo.writeCount, "LargeObject.write() calls"),
        () -> assertEquals(Collections.emptyList(), messagesOf(e.getSuppressed()),
            "failures suppressed onto the BlobOutputStream.close() failure"),
        () -> assertEquals(1, lo.closeCount, "LargeObject.close() calls"));
  }

  static Stream<Arguments> commitOnCloseCases() {
    return Stream.of(
        arguments(Failure.NONE, false, true),
        arguments(Failure.FLUSH, false, false),
        arguments(Failure.NONE, true, true),
        arguments(Failure.FLUSH, true, false));
  }

  /**
   * A large object opened with {@code commitOnClose} is committed only when the buffered bytes
   * reached it, for a stream built over it and for the stream its {@code getOutputStream()}
   * returned. The large object is created in the test's transaction, so it survives the rollback
   * only if {@code close()} committed.
   */
  @ParameterizedTest
  @MethodSource("commitOnCloseCases")
  void closeCommitsOnlyWhenTheFlushSucceeds(Failure failure, boolean fromGetOutputStream,
      boolean expectedCommitted) throws Exception {
    long oid = lom.createLO();
    FailingLargeObject lo =
        new FailingLargeObject(fastpath, oid, failure, con.unwrap(BaseConnection.class), true);
    OutputStream os = fromGetOutputStream ? lo.getOutputStream() : new BlobOutputStream(lo, 1024);
    os.write(PAYLOAD, 0, PAYLOAD.length);
    closeIgnoringFailure(os);
    con.rollback();

    boolean committed = largeObjectExists(oid);
    if (committed) {
      lom.delete(oid);
      con.commit();
    }
    assertEquals(expectedCommitted, committed, "large object committed by close()");
  }

  /**
   * A write the server refuses aborts the transaction, so the release fails as well. The stream
   * is closed all the same, and the release failure is suppressed onto the flush failure.
   */
  @Test
  void aWriteRefusedByTheServerStillClosesTheStream() throws Exception {
    LargeObject lo = lom.open(lom.createLO(), LargeObjectManager.READ);
    BlobOutputStream os = streamWithBufferedPayload(lo);

    IOException e = assertThrows(IOException.class, os::close);

    assertAll(
        () -> assertEquals(Collections.singletonList(PSQLState.IN_FAILED_SQL_TRANSACTION.getState()),
            sqlStatesOf(e.getSuppressed()),
            "SQLStates suppressed onto the BlobOutputStream.close() failure"),
        () -> assertThrows(IOException.class, () -> os.write(new byte[]{4}, 0, 1),
            "BlobOutputStream.write(byte[1], 0, 1) after close()"));
  }
}
