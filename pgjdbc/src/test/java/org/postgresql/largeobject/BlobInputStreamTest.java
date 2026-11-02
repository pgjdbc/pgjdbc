/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGConnection;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

class BlobInputStreamTest {
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
      blob.write(new byte[64]);
    }
  }

  @AfterEach
  void tearDown() throws SQLException {
    TestUtil.closeDB(con);
  }

  private InputStream openStream() throws SQLException {
    return lom.open(loid, LargeObjectManager.READ).getInputStream();
  }

  /**
   * A range that does not fit the array is a programmer error, and {@link InputStream#read(byte[],
   * int, int)} requires {@code IndexOutOfBoundsException} for it. The large object must not have
   * moved either: a range whose length was valid on its own used to fill the read buffer first and
   * fail inside {@code System.arraycopy} only afterwards, leaving the object positioned past bytes
   * the caller never received. A negative length did not even fail: it reached the end of the
   * method and came back as -1, which a caller reads as end of stream.
   *
   * <p>The last row pins the overflow-free form of the check. Written as
   * {@code off + len > dest.length} it would wrap to {@link Integer#MIN_VALUE} and accept the
   * call; the exception would still arrive from {@code System.arraycopy}, so it is the position
   * assertion that separates the two.</p>
   *
   * @param off offset into the destination array
   * @param len number of bytes to ask for
   */
  @ParameterizedTest
  @CsvSource({"0, -1", "-1, 4", "-1, 0", "8, 4", "5, 4", "0, 9", "9, 0", "2147483647, 1"})
  void readRejectsRangeOutsideArray(int off, int len) throws Exception {
    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      InputStream bis = blob.getInputStream();
      byte[] dest = new byte[8];
      assertThrows(IndexOutOfBoundsException.class, () -> bis.read(dest, off, len));
      assertEquals(0, blob.tell(), "large object position after a rejected range");
    }
  }

  /**
   * A null array is a {@code NullPointerException} whatever the rest of the range says. The second
   * row is the one that pins the order: the range check dereferences {@code dest.length} to build
   * its message, so a check that ran first would report the range instead.
   *
   * @param off offset into the destination array
   * @param len number of bytes to ask for
   */
  @ParameterizedTest
  @CsvSource({"0, 4", "-1, -1"})
  void readRejectsNullArray(int off, int len) throws Exception {
    try (InputStream bis = openStream()) {
      assertThrows(NullPointerException.class, () -> bis.read(null, off, len));
    }
  }

  /**
   * A closed stream reports itself closed before it reports a bad range. Callers of
   * {@code read} are written for the checked {@link java.io.IOException} that reports a closed
   * stream, and the range check must not turn that into an unchecked failure.
   */
  @Test
  void readAfterCloseReportsClosedStreamRatherThanRange() throws Exception {
    InputStream bis = openStream();
    bis.close();
    byte[] dest = new byte[8];
    assertThrows(IOException.class, () -> bis.read(dest, 5, 200));
    assertThrows(IOException.class, () -> bis.read(dest, 0, -1));
  }

  /**
   * A range that fits but asks for nothing reads nothing, including at the very end of the array,
   * which is the boundary the bounds check must not overshoot.
   *
   * @param off offset into the destination array
   */
  @ParameterizedTest
  @CsvSource({"0", "4", "8"})
  void readAcceptsEmptyRange(int off) throws Exception {
    try (InputStream bis = openStream()) {
      assertEquals(0, bis.read(new byte[8], off, 0));
    }
  }

  @Test
  void readAcceptsRangeUpToTheEndOfTheArray() throws Exception {
    try (InputStream bis = openStream()) {
      byte[] dest = new byte[8];
      assertEquals(4, bis.read(dest, 4, 4));
    }
  }

  /**
   * A rejected range must not have cost a round-trip, and must leave the stream where it was.
   */
  @Test
  void readKeepsPositionAfterRejectingRange() throws Exception {
    try (InputStream bis = openStream()) {
      byte[] dest = new byte[8];
      assertEquals(8, bis.read(dest, 0, 8));
      assertThrows(IndexOutOfBoundsException.class, () -> bis.read(dest, 0, -1));
      assertEquals(8, bis.read(dest, 0, 8));

      // The connection is still usable, so nothing half-finished was left on the wire
      try (Statement stmt = con.createStatement()) {
        stmt.execute("SELECT 1");
      }
    }
  }
}
