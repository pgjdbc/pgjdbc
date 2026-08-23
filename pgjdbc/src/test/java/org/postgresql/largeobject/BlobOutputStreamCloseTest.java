/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGConnection;
import org.postgresql.fastpath.Fastpath;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;

class BlobOutputStreamCloseTest {
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

  private static Throwable causeOf(Throwable e) {
    Throwable cause = e.getCause();
    assertTrue(cause != null, "expected " + e + " to carry a cause");
    return cause;
  }

  private static final String WRITE_REFUSED = "write refused by the test";
  private static final String CLOSE_REFUSED = "close refused by the test";

  /**
   * A large object that can be told to fail either half of {@code close()}: the flush that writes
   * the buffered bytes, or the release of the server-side descriptor. It also records whether the
   * release was attempted at all.
   */
  private static class FailingLargeObject extends LargeObject {
    private final boolean failWrite;
    private final boolean failClose;
    private boolean closeCalled;

    FailingLargeObject(Fastpath fp, long oid, int mode, boolean failWrite, boolean failClose)
        throws SQLException {
      super(fp, oid, mode);
      this.failWrite = failWrite;
      this.failClose = failClose;
    }

    @Override
    public void write(byte[] buf, int off, int len) throws SQLException {
      if (failWrite) {
        throw new PSQLException(WRITE_REFUSED, PSQLState.IO_ERROR);
      }
      super.write(buf, off, len);
    }

    @Override
    public void close() throws SQLException {
      closeCalled = true;
      if (failClose) {
        throw new PSQLException(CLOSE_REFUSED, PSQLState.IO_ERROR);
      }
      super.close();
    }
  }

  private FailingLargeObject largeObject(long oid, boolean failWrite, boolean failClose)
      throws SQLException {
    return new FailingLargeObject(fastpath, oid, LargeObjectManager.READWRITE, failWrite,
        failClose);
  }

  /**
   * When the final flush fails, {@code close()} still has to release the server-side descriptor
   * and mark the stream closed. Otherwise the descriptor stays open for the rest of the
   * transaction, a second {@code close()} retries a flush that already failed, and the stream
   * keeps accepting writes to a large object the caller believes is gone.
   */
  @Test
  void closeReleasesDescriptorWhenFlushFails() throws Exception {
    long oid = lom.createLO();
    FailingLargeObject lo = largeObject(oid, true, false);
    BlobOutputStream os = new BlobOutputStream(lo, 1024);
    os.write(new byte[]{1, 2, 3}, 0, 3);

    assertThrows(IOException.class, os::close, "close() must report the failed flush");
    assertTrue(lo.closeCalled, "close() must release the large object descriptor anyway");

    os.close();

    assertThrows(IOException.class, () -> os.write(42),
        "the stream must refuse a single-byte write once closed");
    assertThrows(IOException.class, () -> os.write(new byte[]{4}, 0, 1),
        "the stream must refuse an array write once closed");

    lom.delete(oid);
  }

  /**
   * The same failure on the stream {@link LargeObject#getOutputStream()} handed out, which is the
   * only state where the callback between the two classes fires: {@code LargeObject.close()}
   * flushes that stream, so the buffered bytes have to be dropped before it runs. Otherwise the
   * second attempt fails the same way and hangs a suppressed exception off the failure the caller
   * sees. This is the path the driver itself takes in {@code PgPreparedStatement.createBlob}.
   */
  @Test
  void closeOfTheHandedOutStreamDoesNotRetryTheFailedFlush() throws Exception {
    long oid = lom.createLO();
    FailingLargeObject lo = largeObject(oid, true, false);
    OutputStream os = lo.getOutputStream();
    os.write(new byte[]{1, 2, 3}, 0, 3);

    IOException e = assertThrows(IOException.class, os::close);
    assertEquals(0, e.getSuppressed().length,
        "close() must not retry the flush that already failed: " + Arrays.toString(
            e.getSuppressed()));
    assertTrue(lo.closeCalled, "close() must release the large object descriptor anyway");

    lom.delete(oid);
  }

  /**
   * When the flush succeeds but releasing the descriptor does not, {@code close()} has to report
   * that failure rather than swallow it, and the stream still counts as closed: there is nothing
   * left for a retry to accomplish.
   */
  @Test
  void closeReportsAFailureToReleaseTheDescriptor() throws Exception {
    long oid = lom.createLO();
    FailingLargeObject lo = largeObject(oid, false, true);
    BlobOutputStream os = new BlobOutputStream(lo, 1024);
    os.write(new byte[]{1, 2, 3}, 0, 3);

    IOException e = assertThrows(IOException.class, os::close);
    assertEquals(CLOSE_REFUSED, causeOf(e).getMessage(),
        "close() must report the failure to release the descriptor, not some other one");
    assertThrows(IOException.class, () -> os.write(42), "the stream must count as closed");
    os.close();

    lom.delete(oid);
  }

  /**
   * Both halves fail. The flush failure is the one the caller needs, because it says why the bytes
   * did not land, so it stays the exception that propagates and the failure to release the
   * descriptor is attached to it. Hoisting the release into a {@code finally} of its own would
   * compile, pass every other test here, and quietly reverse that.
   */
  @Test
  void closeSuppressesTheDescriptorFailureOntoTheFlushFailure() throws Exception {
    long oid = lom.createLO();
    FailingLargeObject lo = largeObject(oid, true, true);
    BlobOutputStream os = new BlobOutputStream(lo, 1024);
    os.write(new byte[]{1, 2, 3}, 0, 3);

    IOException e = assertThrows(IOException.class, os::close);
    assertEquals(WRITE_REFUSED, causeOf(e).getMessage(),
        "the flush failure must be the one that propagates");
    assertEquals(1, e.getSuppressed().length,
        "the failure to release the descriptor must be attached, not dropped: "
            + Arrays.toString(e.getSuppressed()));
    assertEquals(CLOSE_REFUSED, e.getSuppressed()[0].getMessage(),
        "the suppressed failure must be the one from releasing the descriptor");
    assertTrue(lo.closeCalled, "close() must attempt the release even after a failed flush");
    assertThrows(IOException.class, () -> os.write(42), "the stream must count as closed");

    lom.delete(oid);
  }

  /**
   * The ordinary path: the buffered bytes reach the large object, the descriptor is released, and
   * closing twice is not an error.
   */
  @Test
  void closeFlushesAndIsIdempotent() throws Exception {
    long oid = lom.createLO();
    byte[] payload = {1, 2, 3, 4, 5};
    try (LargeObject lo = lom.open(oid)) {
      BlobOutputStream os = new BlobOutputStream(lo, 1024);
      os.write(payload, 0, payload.length);
      os.close();
      os.close();
    }

    try (LargeObject lo = lom.open(oid)) {
      assertArrayEquals(payload, lo.read(lo.size()), "large object contents after close()");
    }
    lom.delete(oid);
  }

  /**
   * {@code LargeObject.close()} flushes the stream it handed out, so the stream must survive being
   * closed from that side and must not write the buffered bytes twice.
   */
  @Test
  void closingTheLargeObjectFlushesTheStreamItHandedOut() throws Exception {
    long oid = lom.createLO();
    byte[] payload = {1, 2, 3, 4, 5};
    LargeObject lo = lom.open(oid);
    lo.getOutputStream().write(payload, 0, payload.length);
    lo.close();

    try (LargeObject reopened = lom.open(oid)) {
      assertArrayEquals(payload, reopened.read(reopened.size()),
          "large object contents after LargeObject.close()");
    }
    lom.delete(oid);
  }
}
