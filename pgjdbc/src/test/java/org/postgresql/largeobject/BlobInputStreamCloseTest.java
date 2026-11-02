/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * What {@link BlobInputStream#close()} leaves behind when releasing the descriptor fails.
 */
@SuppressWarnings("deprecation") // support for deprecated Fastpath API
class BlobInputStreamCloseTest {
  private static final String CLOSE_REFUSED = "close refused by the test";

  private Connection con;
  private LargeObjectManager lom;
  private Fastpath fastpath;
  private long loid;

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    con.setAutoCommit(false);
    PGConnection pgCon = con.unwrap(PGConnection.class);
    lom = pgCon.getLargeObjectAPI();
    fastpath = pgCon.getFastpathAPI();
    loid = lom.createLO();
    try (LargeObject blob = lom.open(loid, LargeObjectManager.WRITE)) {
      blob.write(new byte[64]);
    }
  }

  @AfterEach
  void tearDown() throws SQLException {
    TestUtil.closeDB(con);
  }

  /**
   * A large object whose descriptor refuses to be released, and which records whether the release
   * was attempted.
   */
  private static class FailingLargeObject extends LargeObject {
    private int closeCalls;

    FailingLargeObject(Fastpath fp, long oid, int mode) throws SQLException {
      super(fp, oid, mode);
    }

    @Override
    public void close() throws SQLException {
      closeCalls++;
      throw new PSQLException(CLOSE_REFUSED, PSQLState.IO_ERROR);
    }
  }

  private FailingLargeObject failingLargeObject() throws SQLException {
    return new FailingLargeObject(fastpath, loid, LargeObjectManager.READ);
  }

  /**
   * The failure reaches the caller, wrapped, so nobody mistakes the descriptor for released.
   */
  @Test
  void closeReportsAFailedRelease() throws Exception {
    LargeObject blob = failingLargeObject();
    InputStream bis = blob.getInputStream();

    IOException e = assertThrows(IOException.class, bis::close);
    assertTrue(e.getCause() instanceof SQLException, () -> "cause of " + e);
    assertEquals(CLOSE_REFUSED, e.getCause().getMessage());
  }

  /**
   * The stream is closed even so. It used to keep the large object, so it went on serving bytes
   * from a descriptor the caller had been told to consider gone.
   */
  @Test
  void closeClosesTheStreamEvenWhenTheReleaseFails() throws Exception {
    LargeObject blob = failingLargeObject();
    InputStream bis = blob.getInputStream();
    assertEquals(0, bis.read());

    assertThrows(IOException.class, bis::close);

    // The stream reports itself closed, rather than passing on the failure it met while closing.
    // Checked through the cause rather than the message, which is translated
    IOException afterClose = assertThrows(IOException.class, bis::read);
    assertNull(afterClose.getCause(), () -> "unexpected cause under " + afterClose);
  }

  /**
   * A second close is the no-op {@link java.io.Closeable} requires, and does not ask the server
   * again. Retrying a release that has already failed only turns the caller's cleanup into a
   * second failure.
   */
  @Test
  void closeIsIdempotentAfterAFailedRelease() throws Exception {
    FailingLargeObject blob = failingLargeObject();
    InputStream bis = blob.getInputStream();

    assertThrows(IOException.class, bis::close);
    bis.close();
    assertEquals(1, blob.closeCalls, "attempts to release the descriptor");
  }
}
