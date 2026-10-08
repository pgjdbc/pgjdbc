/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGConnection;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Fails when a write reaches the server with a range that runs past the end of its array.
 *
 * <p>{@code PGStream.send} pads such a range with zero bytes rather than failing, so the bytes the
 * array does not have are stored as data. The range has to be rejected before the request is
 * built, and {@link LargeObject#write(byte[], int, int)} is where every caller-supplied range
 * arrives: {@link java.sql.Blob#setBytes(long, byte[], int, int)} calls it directly.</p>
 */
class LargeObjectWriteBoundsTest {
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
  void writeRejectsARangePastTheEndOfTheArray() throws Exception {
    long oid = lom.createLO();
    try (LargeObject lo = lom.open(oid)) {
      assertThrows(IndexOutOfBoundsException.class, () -> lo.write(new byte[10], 5, 200));
      assertEquals(0, lo.size(), "nothing may reach the large object");
    }
    lom.delete(oid);
  }

  @Test
  void writeRejectsNegativeOffsetsAndLengths() throws Exception {
    long oid = lom.createLO();
    try (LargeObject lo = lom.open(oid)) {
      byte[] b = new byte[10];
      assertThrows(IndexOutOfBoundsException.class, () -> lo.write(b, -1, 1));
      assertThrows(IndexOutOfBoundsException.class, () -> lo.write(b, 0, -1));
      assertThrows(IndexOutOfBoundsException.class, () -> lo.write(b, 11, 0));
      assertEquals(0, lo.size(), "nothing may reach the large object");
    }
    lom.delete(oid);
  }

  /**
   * A length near {@link Integer#MAX_VALUE} makes {@code off + len} wrap negative, so a check
   * written that way accepts the range and the padding stores what the array does not have. The
   * check subtracts instead, and this pins the difference: it is the one input on which the two
   * spellings disagree.
   */
  @Test
  void writeRejectsALengthThatWouldWrapTheBoundsCheck() throws Exception {
    long oid = lom.createLO();
    try (LargeObject lo = lom.open(oid)) {
      assertThrows(IndexOutOfBoundsException.class,
          () -> lo.write(new byte[10], 3, Integer.MAX_VALUE));
      assertEquals(0, lo.size(), "nothing may reach the large object");
    }
    lom.delete(oid);
  }

  /**
   * A null array is a null array whatever the offset says, so the offset must not decide which
   * exception comes back.
   */
  @Test
  void writeRejectsANullArray() throws Exception {
    long oid = lom.createLO();
    try (LargeObject lo = lom.open(oid)) {
      NullPointerException e =
          assertThrows(NullPointerException.class, () -> lo.write(null, 0, 5));
      assertNotNull(e.getMessage(), "the exception has to say which argument was null");
      assertThrows(NullPointerException.class, () -> lo.write(null, -1, -1));
      assertEquals(0, lo.size(), "nothing may reach the large object");
    }
    lom.delete(oid);
  }

  /**
   * A window from the middle of the array has to arrive as that window. Every other accepted write
   * here starts at zero or writes nothing, so without this a check that also rejected a non-zero
   * offset would pass, and a write that shifted the window would go unnoticed.
   */
  @Test
  void writeStoresTheWindowTheCallerAskedFor() throws Exception {
    long oid = lom.createLO();
    byte[] source = payload(1000);
    byte[] expected = new byte[300];
    System.arraycopy(source, 137, expected, 0, 300);
    try (LargeObject lo = lom.open(oid)) {
      lo.write(source, 137, 300);
      lo.seek(0);
      assertArrayEquals(expected, lo.read(lo.size()), "large object contents");
    }
    lom.delete(oid);
  }

  /**
   * {@code off == buf.length} with {@code len == 0} is an empty range at the very end of the
   * array, and a write of nothing is not an error.
   */
  @Test
  void writeAcceptsAnEmptyRangeAtTheEndOfTheArray() throws Exception {
    long oid = lom.createLO();
    try (LargeObject lo = lom.open(oid)) {
      lo.write(new byte[10], 10, 0);
      assertEquals(0, lo.size(), "an empty range writes nothing");
    }
    lom.delete(oid);
  }

  /**
   * The path a caller reaches through JDBC. It does not pass through {@code BlobOutputStream}, so
   * the check has to sit where every caller-supplied range arrives.
   */
  @Test
  void blobSetBytesRejectsARangePastTheEndOfTheArray() throws Exception {
    long oid = lom.createLO();
    try (LargeObject seed = lom.open(oid)) {
      seed.write(new byte[]{1, 2, 3}, 0, 3);
    }
    try (Statement st = con.createStatement()) {
      st.execute("create temp table lo_bounds (id int, data oid)");
    }
    try (PreparedStatement ps = con.prepareStatement("insert into lo_bounds values (1, ?)")) {
      ps.setLong(1, oid);
      ps.executeUpdate();
    }

    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("select data from lo_bounds where id = 1")) {
      assertTrue(rs.next(), "the row went missing");
      Blob blob = rs.getBlob(1);
      assertThrows(IndexOutOfBoundsException.class, () -> blob.setBytes(1, new byte[10], 5, 200));
    }

    try (LargeObject lo = lom.open(oid)) {
      assertArrayEquals(new byte[]{1, 2, 3}, lo.read(lo.size()),
          "the rejected setBytes must leave the blob as it was");
    }
    lom.delete(oid);
  }

  /**
   * Aperiodic, so a window that arrived shifted cannot look like the one that was asked for.
   */
  private static byte[] payload(int length) {
    byte[] payload = new byte[length];
    new java.util.Random(length * 6364136223846793005L + 1442695040888963407L).nextBytes(payload);
    return payload;
  }
}
