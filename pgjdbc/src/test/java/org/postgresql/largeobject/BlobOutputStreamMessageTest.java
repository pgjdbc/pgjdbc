/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGConnection;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * The failure messages name a large object by its OID, and an OID is an identifier rather than a
 * quantity: it goes into {@code lo_unlink} or a {@code pg_largeobject} query as the reader typed
 * it. {@link org.postgresql.util.GT#tr} formats through {@code MessageFormat}, which groups a
 * number by the reader's locale, so an OID passed as one arrives as {@code 2,180,387}.
 */
class BlobOutputStreamMessageTest {
  private Connection con;
  private LargeObjectManager lom;

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    con.setAutoCommit(false);
    lom = con.unwrap(PGConnection.class).getLargeObjectAPI();
  }

  @AfterEach
  void tearDown() throws SQLException {
    TestUtil.closeDB(con);
  }

  @Test
  void theWriteFailureNamesTheLargeObjectByItsOid() throws Exception {
    long oid = lom.createLO();
    LargeObject lo = lom.open(oid);
    BlobOutputStream os = new BlobOutputStream(lo, 64);
    lo.close();

    IOException e = assertThrows(IOException.class, () -> os.write(new byte[100], 0, 100));
    assertMessageCarries(e, oid);

    lom.delete(oid);
  }

  @Test
  void theFlushFailureNamesTheLargeObjectByItsOid() throws Exception {
    long oid = lom.createLO();
    LargeObject lo = lom.open(oid);
    BlobOutputStream os = new BlobOutputStream(lo, 1024);
    os.write(new byte[]{1, 2, 3}, 0, 3);
    lo.close();

    IOException e = assertThrows(IOException.class, os::flush);
    assertMessageCarries(e, oid);

    lom.delete(oid);
  }

  @Test
  void theSingleByteWriteFailureNamesTheLargeObjectByItsOid() throws Exception {
    long oid = lom.createLO();
    LargeObject lo = lom.open(oid);
    BlobOutputStream os = new BlobOutputStream(lo, 1);
    lo.close();

    // The first byte only fills the one-byte buffer; the second is the one that has to flush it
    os.write(42);
    IOException e = assertThrows(IOException.class, () -> os.write(43));
    assertMessageCarries(e, oid);

    lom.delete(oid);
  }

  /**
   * A freshly created large object is well past four digits, so an OID that had been grouped would
   * not contain its own digits any more, whatever separator the locale uses.
   */
  private static void assertMessageCarries(IOException e, long oid) {
    String message = e.getMessage();
    assertTrue(oid > 9999, "the test needs an OID long enough to be grouped, got " + oid);
    assertTrue(message != null && message.contains(Long.toString(oid)),
        "expected the message to name large object " + oid + ", got: " + message);
  }
}
