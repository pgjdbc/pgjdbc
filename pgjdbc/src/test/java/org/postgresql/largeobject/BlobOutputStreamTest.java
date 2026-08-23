/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGConnection;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

class BlobOutputStreamTest {
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

  /**
   * Runs {@code body} against a fresh large object and returns its full contents.
   */
  private byte[] withLargeObject(int bufferSize, StreamAction body) throws Exception {
    long oid = lom.createLO();
    try (LargeObject lo = lom.open(oid)) {
      BlobOutputStream os = new BlobOutputStream(lo, bufferSize);
      body.accept(os);
      os.flush();
      lo.seek(0);
      return lo.read(lo.size());
    } finally {
      lom.delete(oid);
    }
  }

  interface StreamAction {
    void accept(BlobOutputStream os) throws IOException;
  }

  private static byte[] payload(int length) {
    byte[] payload = new byte[length];
    for (int i = 0; i < length; i++) {
      payload[i] = (byte) i;
    }
    return payload;
  }

  /**
   * A range that runs past the end of the array must be rejected before any byte reaches the
   * server, and the rejected call must leave the stream holding exactly what it held before.
   *
   * <p>The bytes written first put the stream in the state where the two buffer sizes take
   * different paths, and the two parameters buy different things. With the 64-byte buffer those
   * bytes are already on the server, so the range goes straight to {@code PGStream.send}, which
   * pads a range past the end of the array with zeros rather than failing: the large object
   * silently gained 195 zero bytes, and only this parameter catches that. With the 512KiB buffer
   * they are still in the internal buffer, so the range reaches {@code System.arraycopy}, which
   * threw before storing anything, but with {@code ArrayIndexOutOfBoundsException} rather than
   * the exception {@code OutputStream} specifies. That parameter guards the exception type, not
   * the contents.</p>
   */
  @ParameterizedTest
  @ValueSource(ints = {64, 512 * 1024})
  void writeRejectsRangePastEndOfArray(int bufferSize) throws Exception {
    byte[] written = payload(100);
    byte[] stored = withLargeObject(bufferSize, os -> {
      os.write(written, 0, written.length);
      byte[] b = new byte[10];
      assertThrows(IndexOutOfBoundsException.class, () -> os.write(b, 5, 200));
    });
    assertArrayEquals(written, stored, "large object contents after the rejected write");
  }

  /**
   * {@link java.io.OutputStream#write(byte[], int, int)} specifies
   * {@link NullPointerException} for a null array, whatever the offset and length are.
   */
  @Test
  void writeRejectsNullArray() throws Exception {
    withLargeObject(1024, os -> {
      assertThrows(NullPointerException.class, () -> os.write(null, 0, 5));
      assertThrows(NullPointerException.class, () -> os.write(null, -1, -1));
    });
  }

  @ParameterizedTest
  @ValueSource(ints = {64, 512 * 1024})
  void writeRejectsNegativeLength(int bufferSize) throws Exception {
    withLargeObject(bufferSize, os -> {
      byte[] b = new byte[10];
      assertThrows(IndexOutOfBoundsException.class, () -> os.write(b, 0, -1));
    });
  }

  @ParameterizedTest
  @ValueSource(ints = {64, 512 * 1024})
  void writeRejectsNegativeOffset(int bufferSize) throws Exception {
    withLargeObject(bufferSize, os -> {
      byte[] b = new byte[10];
      assertThrows(IndexOutOfBoundsException.class, () -> os.write(b, -1, 1));
    });
  }

  @Test
  void writeRejectsOffsetPastEndOfArray() throws Exception {
    withLargeObject(1024, os -> {
      byte[] b = new byte[10];
      assertThrows(IndexOutOfBoundsException.class, () -> os.write(b, 11, 0));
    });
  }

  /**
   * {@code off == b.length} with {@code len == 0} is an empty range at the very end of the array,
   * which {@link java.io.OutputStream#write(byte[], int, int)} accepts.
   */
  @Test
  void writeAcceptsEmptyRangeAtEndOfArray() throws Exception {
    byte[] stored = withLargeObject(1024, os -> os.write(new byte[10], 10, 0));
    assertArrayEquals(new byte[0], stored, "large object contents after an empty write");
  }

  /**
   * A write to a closed stream reports the stream as closed even when the range is invalid too.
   * That is what the stream did before it validated ranges, and what {@code write(int)} does, so
   * the range check must not turn a checked {@link IOException} into an unchecked one.
   */
  @Test
  void writeAfterCloseReportsClosedStreamRatherThanRange() throws Exception {
    long oid = lom.createLO();
    try (LargeObject lo = lom.open(oid)) {
      BlobOutputStream os = new BlobOutputStream(lo, 1024);
      os.close();
      byte[] b = new byte[10];
      assertThrows(IOException.class, () -> os.write(b, 5, 200));
    } finally {
      lom.delete(oid);
    }
  }

  /**
   * A rejected write must leave the stream usable.
   */
  @ParameterizedTest
  @ValueSource(ints = {64, 512 * 1024})
  void writeAfterRejectedRangeStoresTheRequestedBytes(int bufferSize) throws Exception {
    byte[] payload = payload(3000);
    byte[] stored = withLargeObject(bufferSize, os -> {
      assertThrows(IndexOutOfBoundsException.class, () -> os.write(new byte[10], 5, 200));
      os.write(payload, 0, payload.length);
    });
    assertEquals(payload.length, stored.length, "large object length");
    assertArrayEquals(payload, stored, "large object contents");
  }
}
