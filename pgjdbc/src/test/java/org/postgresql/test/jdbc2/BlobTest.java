/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.EnabledForServerVersionRange;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;

/**
 * Some simple tests based on problems reported by users. Hopefully these will help prevent previous
 * problems from re-occurring ;-)
 */
class BlobTest {
  private static final String TEST_FILE =  "/test-file.xml";

  private static final int LOOP = 0; // LargeObject API using loop
  private static final int NATIVE_STREAM = 1; // LargeObject API using OutputStream

  /**
   * Largest position {@code BlobInputStream} seeks to without first locating the end of the large
   * object, kept in step with the driver's own constant, which is private. It is
   * {@code INT_MAX * LOBLKSIZE} at the smallest block size PostgreSQL can be built with, so every
   * server accepts a seek this far.
   */
  private static final long MAX_UNCHECKED_SEEK_POSITION = 256L * Integer.MAX_VALUE;

  private Connection con;

  /*
    Only do this once
  */
  @BeforeAll
  static void createLargeBlob() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createTable(con, "testblob", "id name,lo oid");
      con.setAutoCommit(false);
      LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
      long oid = lom.createLO(LargeObjectManager.READWRITE);
      LargeObject blob = lom.open(oid);

      byte[] buf = new byte[256];
      for (int i = 0; i < buf.length; i++) {
        buf[i] = (byte) i;
      }
      // I want to create a large object
      int i = 1024 / buf.length;
      for (int j = i; j > 0; j--) {
        blob.write(buf, 0, buf.length);
      }
      assertEquals(1024, blob.size());
      blob.close();
      try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO testblob(id, lo) VALUES(?,?)")) {
        pstmt.setString(1, "l1");
        pstmt.setLong(2, oid);
        pstmt.executeUpdate();
      }
      con.commit();
    }
  }

  @AfterAll
  static void cleanup() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      try (Statement stmt = con.createStatement()) {
        stmt.execute("SELECT lo_unlink(lo) FROM testblob where id = 'l1'");
      } finally {
        TestUtil.dropTable(con, "testblob");
      }
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    con.setAutoCommit(false);
  }

  @AfterEach
  void tearDown() throws Exception {
    con.setAutoCommit(true);
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SELECT lo_unlink(lo) FROM testblob where id != 'l1'");
      stmt.execute("delete from testblob where id != 'l1'");
    } finally {
      TestUtil.closeDB(con);
    }
  }

  @Test
  void setNull() throws Exception {
    try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO testblob(lo) VALUES (?)")) {

      pstmt.setBlob(1, (Blob) null);
      pstmt.executeUpdate();

      pstmt.setNull(1, Types.BLOB);
      pstmt.executeUpdate();

      pstmt.setObject(1, null, Types.BLOB);
      pstmt.executeUpdate();

      pstmt.setClob(1, (Clob) null);
      pstmt.executeUpdate();

      pstmt.setNull(1, Types.CLOB);
      pstmt.executeUpdate();

      pstmt.setObject(1, null, Types.CLOB);
      pstmt.executeUpdate();
    }
  }

  /**
   * Closing a LargeObject must flush its still-buffered output stream before marking the object
   * closed. See <a href="https://github.com/pgjdbc/pgjdbc/issues/4247">issue 4247</a>.
   */
  @Test
  void closeFlushesBufferedOutputStream() throws Exception {
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
    long oid = lom.createLO(LargeObjectManager.READWRITE);

    byte[] data = "pgjdbc-4247".getBytes(StandardCharsets.US_ASCII);

    LargeObject blob = lom.open(oid, LargeObjectManager.WRITE);
    OutputStream os = blob.getOutputStream();
    // Less than the buffer size, so the bytes stay buffered until close() flushes them
    os.write(data);
    // Close the LargeObject directly without flushing the stream first: this must not throw
    blob.close();

    blob = lom.open(oid, LargeObjectManager.READ);
    try {
      assertArrayEquals(data, blob.read(data.length));
    } finally {
      blob.close();
      lom.delete(oid);
    }
  }

  @Test
  void set() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("INSERT INTO testblob(id,lo) VALUES ('1', lo_creat(-1))");
      ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id = '1'");
      assertTrue(rs.next());

      PreparedStatement pstmt = con.prepareStatement("INSERT INTO testblob(id, lo) VALUES(?,?)");

      Blob blob = rs.getBlob(1);
      pstmt.setString(1, "setObjectTypeBlob");
      pstmt.setObject(2, blob, Types.BLOB);
      assertEquals(1, pstmt.executeUpdate());

      blob = rs.getBlob(1);
      pstmt.setString(1, "setObjectBlob");
      pstmt.setObject(2, blob);
      assertEquals(1, pstmt.executeUpdate());

      blob = rs.getBlob(1);
      pstmt.setString(1, "setBlob");
      pstmt.setBlob(2, blob);
      assertEquals(1, pstmt.executeUpdate());

      Clob clob = rs.getClob(1);
      pstmt.setString(1, "setObjectTypeClob");
      pstmt.setObject(2, clob, Types.CLOB);
      assertEquals(1, pstmt.executeUpdate());

      clob = rs.getClob(1);
      pstmt.setString(1, "setObjectClob");
      pstmt.setObject(2, clob);
      assertEquals(1, pstmt.executeUpdate());

      clob = rs.getClob(1);
      pstmt.setString(1, "setClob");
      pstmt.setClob(2, clob);
      assertEquals(1, pstmt.executeUpdate());
    }
  }

  @ValueSource(ints = {0, 1, 13, 123423})
  @ParameterizedTest
  void setBlobMinusOneLengthAndGivenByteContents(int length) throws Exception {
    byte[] contents = new byte[length];
    ThreadLocalRandom.current().nextBytes(contents);
    try (PreparedStatement pstmt =
             con.prepareStatement("INSERT INTO testblob(id, lo) VALUES (?, ?)")) {
      pstmt.setString(1, "setBlobNegativeLength");
      pstmt.setBlob(2, new SerialBlob(contents) {
        @Override
        public long length() {
          return -1;
        }
      });
      pstmt.executeUpdate();
    }
    // Read the value back and compare with original
    try (Statement stmt = con.createStatement()) {
      try (ResultSet rs =
               stmt.executeQuery("SELECT lo FROM testblob where id = 'setBlobNegativeLength'")) {
        assertTrue(rs.next(), "rs.next()");
        Blob blob = rs.getBlob(1);
        assertArrayEquals(
            contents,
            blob.getBytes(1, contents.length),
            "blob.getBytes(1, contents.length)"
        );
        assertArrayEquals(
            contents,
            blob.getBytes(1, contents.length * 2),
            "blob.getBytes(1, contents.length * 2)"
        );
        assertEquals(contents.length, blob.length(), "blob.length()");
      }
    }
  }

  @ValueSource(ints = {0, 1, 13, 123423})
  @ParameterizedTest
  void setClobMinusOneLengthAndGivenByteContents(int length) throws Exception {
    char[] contents = new char[length];
    for (int i = 0; i < contents.length; i++) {
      contents[i] = (char) ('a' + ThreadLocalRandom.current().nextInt(26));
    }
    try (PreparedStatement pstmt =
             con.prepareStatement("INSERT INTO testblob(id, lo) VALUES (?, ?)")) {
      pstmt.setString(1, "setClobNegativeLength");
      pstmt.setClob(2, new SerialClob(contents) {
        @Override
        public long length() {
          return -1;
        }
      });
      pstmt.executeUpdate();
    }
    // Read the value back and compare with original
    try (Statement stmt = con.createStatement()) {
      try (ResultSet rs =
               stmt.executeQuery("SELECT lo FROM testblob where id = 'setClobNegativeLength'")) {
        assertTrue(rs.next(), "rs.next()");
        Clob clob = rs.getClob(1);
        assertEquals(
            new String(contents),
            clob.getSubString(1, contents.length),
            "clob.getSubString(1, contents.length)"
        );
        assertEquals(
            new String(contents),
            clob.getSubString(1, contents.length * 2),
            "clob.getSubString(1, contents.length * 2)"
        );
        assertEquals(contents.length, clob.length(), "clob.length()");
      }
    }
  }

  /*
   * Tests one method of uploading a blob to the database
   */
  @Test
  void uploadBlob_LOOP() throws Exception {
    assertTrue(uploadFile(TEST_FILE, LOOP) > 0);

    // Now compare the blob & the file. Note this actually tests the
    // InputStream implementation!
    assertTrue(compareBlobsLOAPI(TEST_FILE));
    assertTrue(compareBlobs(TEST_FILE));
    assertTrue(compareClobs(TEST_FILE));
  }

  /*
   * Tests one method of uploading a blob to the database
   */
  @Test
  void uploadBlob_NATIVE() throws Exception {
    assertTrue(uploadFile(TEST_FILE, NATIVE_STREAM) > 0);

    // Now compare the blob & the file. Note this actually tests the
    // InputStream implementation!
    assertTrue(compareBlobs(TEST_FILE));
  }

  @Test
  void markResetStream() throws Exception {
    assertTrue(uploadFile(TEST_FILE, NATIVE_STREAM) > 0);

    try (Statement stmt = con.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id = '/test-file.xml'")) {
        assertTrue(rs.next());

        LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();

        long oid = rs.getLong(1);
        try (LargeObject blob = lom.open(oid)) {
          InputStream bis = blob.getInputStream();

          assertEquals('<', bis.read());
          bis.mark(4);
          assertEquals('?', bis.read());
          assertEquals('x', bis.read());
          assertEquals('m', bis.read());
          assertEquals('l', bis.read());
          bis.reset();
          assertEquals('?', bis.read());
        }
      }
    }
  }

  @Test
  void markResetWithInitialOffset() throws Exception {
    assertTrue(uploadFile(TEST_FILE, NATIVE_STREAM) > 0);

    try (Statement stmt = con.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id = '/test-file.xml'")) {
        assertTrue(rs.next());

        LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();

        long oid = rs.getLong(1);
        try (LargeObject blob = lom.open(oid)) {
          // Position the LargeObject before creating the stream: mark/reset must be relative to
          // this offset, not to the start of the object (issue #3149)
          blob.seek(4);
          InputStream bis = blob.getInputStream();

          assertEquals('l', bis.read());
          bis.reset();
          assertEquals('l', bis.read());
          assertEquals(' ', bis.read());
          bis.mark(4);
          assertEquals('v', bis.read());
          assertEquals('e', bis.read());
          bis.reset();
          assertEquals('v', bis.read());
        }
      }
    }
  }

  @Test
  void skip() throws Exception {
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
    long loid = createMediumLargeObject();

    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      InputStream bis = blob.getInputStream();
      assertEquals(0, bis.read());
      assertEquals(1024L, bis.skip(1024));
      assertEquals(1, bis.read());
      assertEquals(64 * 1024L, bis.skip(64 * 1024));
      assertEquals(65, bis.read());
    }
  }

  @Test
  void skipBackwards() throws Exception {
    assertTrue(uploadFile(TEST_FILE, NATIVE_STREAM) > 0);

    try (Statement stmt = con.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id = '/test-file.xml'")) {
        assertTrue(rs.next());

        LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
        long loid = rs.getLong(1);

        try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
          InputStream bis = blob.getInputStream();
          assertEquals('<', bis.read());
          // This stream does not skip backwards, so skip(-1) is a no-op
          assertEquals(0, bis.skip(-1));
          assertEquals('?', bis.read());
        }
      }
    }
  }

  @Test
  void skipToEnd() throws Exception {
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
    long loid = createMediumLargeObject();

    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      InputStream bis = blob.getInputStream();
      assertEquals(96 * 1024, bis.skip(96 * 1024));
      assertEquals(-1, bis.read());
    }
  }

  @Test
  void skipPastEnd() throws Exception {
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
    long loid = createMediumLargeObject();

    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      // Large objects are sparse: skipping past the end is allowed and reads then return -1
      InputStream bis = blob.getInputStream();
      assertEquals(1024 * 1024, bis.skip(1024 * 1024));
      assertEquals(-1, bis.read());
      assertEquals(1024, bis.skip(1024));
      assertEquals(-1, bis.read());
    }
  }

  /**
   * A skip that lands at or below {@link #MAX_UNCHECKED_SEEK_POSITION} keeps the sparse behavior:
   * the stream seeks blind, lands past the end of the object, and reports the full distance. This
   * is the cheap side of the threshold, so a change that starts locating the end here would cost
   * every ordinary skip a round-trip.
   *
   * <p>Both distances are past {@link Integer#MAX_VALUE}, so the seek needs {@code lo_lseek64}.
   * On an older server the ceiling is 2 GiB and the stream locates the end of the object instead,
   * which is {@link #skipPastIntMaxWithout64BitOffsets}'s subject rather than this one's.</p>
   *
   * @param n distance to skip from the start of the stream, so also the position it lands on
   */
  @ParameterizedTest
  @ValueSource(longs = {MAX_UNCHECKED_SEEK_POSITION - 1, MAX_UNCHECKED_SEEK_POSITION})
  @EnabledForServerVersionRange(gte = "9.3")
  void skipUpToUncheckedSeekLimit(long n) throws Exception {
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
    long loid = createMediumLargeObject();

    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      InputStream bis = blob.getInputStream();
      assertEquals(n, bis.skip(n), () -> "skip(" + n + ") is within the distance the stream seeks "
          + "to without locating the end of the object, so it should report the full distance");
      assertEquals(-1, bis.read());
    }
  }

  /**
   * Without {@code lo_lseek64} the stream cannot seek past {@link Integer#MAX_VALUE}, so from
   * there, rather than from {@link #MAX_UNCHECKED_SEEK_POSITION}, it locates the end of the object
   * and stops. Here a blind seek fails before it reaches the server rather than aborting the
   * transaction: {@code LargeObjectManager} takes the large object function oids from
   * {@code pg_proc}, and a pre-9.3 catalog has no {@code lo_lseek64} row, so the skip dies on
   * "The fastpath function lo_lseek64 is unknown". The skip is lost either way.
   *
   * <p>This is the pre-9.3 half of {@link #skipUpToUncheckedSeekLimit}. 3 GiB sits inside the band
   * where the two ceilings disagree, which makes it the only kind of distance that tells them
   * apart: everything shorter is a blind seek under either ceiling, and everything longer is
   * clamped under either.</p>
   */
  @Test
  @EnabledForServerVersionRange(lt = "9.3")
  void skipPastIntMaxWithout64BitOffsets() throws Exception {
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
    long loid = createMediumLargeObject();

    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      InputStream bis = blob.getInputStream();
      assertEquals(96 * 1024, bis.skip(3L * 1024 * 1024 * 1024));
      assertEquals(-1, bis.read());
    }
  }

  /**
   * A skip that lands past {@link #MAX_UNCHECKED_SEEK_POSITION} stops at the end of the object.
   * Beyond that the server may refuse the seek, and the refusal is a failed fastpath call that
   * aborts the caller's transaction, so the stream must never issue it.
   *
   * <p>A server built with the default 8 KiB block size accepts a seek to
   * {@code MAX_UNCHECKED_SEEK_POSITION + 1}, since its {@code MAX_LARGE_OBJECT_SIZE} is eight
   * times larger, and the driver stops earlier because it does not ask for the block size. The two
   * {@code Long.MAX_VALUE} distances are past what any build accepts.</p>
   *
   * @param n distance to skip, large enough to land past the largest position the stream seeks to
   *     without locating the end of the object
   */
  @ParameterizedTest
  @ValueSource(longs = {MAX_UNCHECKED_SEEK_POSITION + 1, Long.MAX_VALUE / 2, Long.MAX_VALUE})
  void skipPastMax(long n) throws Exception {
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
    long loid = createMediumLargeObject();

    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      InputStream bis = blob.getInputStream();
      assertEquals(0, bis.read());
      assertEquals(96 * 1024 - 1, bis.skip(n), () -> "skip(" + n + ") should stop at the end of "
          + "the large object, which holds 96 * 1024 bytes, one of which is already read");
      assertEquals(-1, bis.read());
    }

    // The transaction is still usable, so no seek was rejected
    try (Statement stmt = con.createStatement()) {
      assertTrue(stmt.execute("SELECT 1"));
    }
  }

  @Test
  void skipPastMaxWhenAlreadyPastEnd() throws Exception {
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
    long loid = createMediumLargeObject();

    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      InputStream bis = blob.getInputStream();
      // Large objects are sparse, so this lands past the end without an error
      assertEquals(1024 * 1024, bis.skip(1024 * 1024));
      // Nothing left to skip, and skip does not move backwards to the end of the object
      assertEquals(0, bis.skip(Long.MAX_VALUE));
      assertEquals(-1, bis.read());
    }
  }

  /**
   * A limit below zero leaves the end of the stream before its start, and skip reports that as
   * nothing skipped. Where the large object sits decides how the stream would otherwise break,
   * since the limit is resolved relative to that position: from offset 0 it stays below zero and
   * the seek is one the server refuses, taking the transaction with it; from a later offset it
   * resolves above zero and the seek succeeds, but skip returns a negative count, which
   * {@link InputStream#skip(long)} does not allow.
   *
   * @param startOffset where the large object is positioned when the stream is built
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1024})
  void skipWithNegativeLimit(int startOffset) throws Exception {
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
    long loid = createMediumLargeObject();

    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      blob.seek(startOffset);
      // -1 is the "no limit" sentinel, so -2 is the negative limit closest to it
      InputStream bis = blob.getInputStream(-2);
      assertEquals(0, bis.skip(50));
      assertEquals(-1, bis.read());
    }

    // The transaction is still usable, so no seek was rejected
    try (Statement stmt = con.createStatement()) {
      assertTrue(stmt.execute("SELECT 1"));
    }
  }

  @Test
  void skipPastMaxWithLimit() throws Exception {
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
    long loid = createMediumLargeObject();

    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      // The limit is closer than the end of the object, so it is what bounds the skip
      InputStream bis = blob.getInputStream(65 * 1024);
      assertEquals(65 * 1024L, bis.skip(Long.MAX_VALUE));
      assertEquals(-1, bis.read());
    }
  }

  @Test
  void skipWithInitialOffset() throws Exception {
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
    long loid = createMediumLargeObject();

    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      blob.seek(1024);

      InputStream bis = blob.getInputStream();
      assertEquals(1, bis.read());
      assertEquals(1024L, bis.skip(1024));
      assertEquals(2, bis.read());
      assertEquals(64 * 1024L, bis.skip(64 * 1024));
      assertEquals(66, bis.read());
    }
  }

  @Test
  void skipWithLimit() throws Exception {
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
    long loid = createMediumLargeObject();

    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      InputStream bis = blob.getInputStream(65 * 1024);
      assertEquals(0, bis.read());
      assertEquals(64 * 1024L, bis.skip(64 * 1024));
      assertEquals(64, bis.read());
      assertEquals(1022L, bis.skip(1024));
      assertEquals(-1, bis.read());
    }
  }

  @Test
  void getBytesOffset() throws Exception {
    assertTrue(uploadFile(TEST_FILE, NATIVE_STREAM) > 0);

    try (Statement stmt = con.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id = '/test-file.xml'")) {

        assertTrue(rs.next());

        Blob lob = rs.getBlob(1);
        byte[] data = lob.getBytes(2, 4);
        assertEquals(4, data.length);
        assertEquals('?', data[0]);
        assertEquals('x', data[1]);
        assertEquals('m', data[2]);
        assertEquals('l', data[3]);
      }
    }
  }

  @Test
  void multipleStreams() throws Exception {
    assertTrue(uploadFile(TEST_FILE, NATIVE_STREAM) > 0);

    try (Statement stmt = con.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id = '/test-file.xml'")) {
        assertTrue(rs.next());

        Blob lob = rs.getBlob(1);
        byte[] data = new byte[2];

        InputStream is = lob.getBinaryStream();
        assertEquals(data.length, is.read(data));
        assertEquals('<', data[0]);
        assertEquals('?', data[1]);
        is.close();

        is = lob.getBinaryStream();
        assertEquals(data.length, is.read(data));
        assertEquals('<', data[0]);
        assertEquals('?', data[1]);
        is.close();
      }
    }
  }

  @Test
  void parallelStreams() throws Exception {
    assertTrue(uploadFile(TEST_FILE, NATIVE_STREAM) > 0);

    try (Statement stmt = con.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id = '/test-file.xml'")) {
        assertTrue(rs.next());

        Blob lob = rs.getBlob(1);
        InputStream is1 = lob.getBinaryStream();
        InputStream is2 = lob.getBinaryStream();

        while (true) {
          int i1 = is1.read();
          int i2 = is2.read();
          assertEquals(i1, i2);
          if (i1 == -1) {
            break;
          }
        }

        is1.close();
        is2.close();
      }
    }
  }

  @Test
  void largeLargeObject() throws Exception {
    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_3)) {
      return;
    }

    try (Statement stmt = con.createStatement()) {
      stmt.execute("INSERT INTO testblob(id,lo) VALUES ('1', lo_creat(-1))");
      try (ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id ='1'")) {
        assertTrue(rs.next());

        Blob lob = rs.getBlob(1);
        long length = ((long) Integer.MAX_VALUE) + 1024;
        lob.truncate(length);
        assertEquals(length, lob.length());
      }
    }
  }

  @Test
  void largeObjectRead() throws Exception {
    con.setAutoCommit(false);
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
    try (Statement stmt = con.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id='l1'")) {
        assertTrue(rs.next());

        long oid = rs.getLong(1);
        try (InputStream lois = lom.open(oid).getInputStream()) {
          // read half of the data with read
          for (int j = 0; j < 512; j++) {
            lois.read();
          }
          byte[] buf2 = new byte[512];
          lois.read(buf2, 0, 512);
        }
      }
    }
    con.commit();
  }

  @Test
  void largeObjectRead1() throws Exception {
    con.setAutoCommit(false);
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
    try (Statement stmt = con.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id='l1'")) {
        assertTrue(rs.next());

        long oid = rs.getLong(1);
        try (InputStream lois = lom.open(oid).getInputStream(512, 1024)) {
          // read one byte
          assertEquals(0, lois.read());
          byte[] buf2 = new byte[1024];
          int bytesRead = lois.read(buf2, 0, buf2.length);
          assertEquals(1023, bytesRead);
          assertEquals(1, buf2[0]);
        }
      }
    }
    con.commit();
  }

  /*
   * Helper - uploads a file into a blob using old style methods. We use this because it always
   * works, and we can use it as a base to test the new methods.
   */
  private long uploadFile(String file, int method) throws Exception {
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();

    InputStream fis = getClass().getResourceAsStream(file);

    long oid = lom.createLO(LargeObjectManager.READWRITE);
    LargeObject blob = lom.open(oid);

    int s;
    int t;
    byte[] buf;
    OutputStream os;

    switch (method) {
      case LOOP:
        buf = new byte[2048];
        t = 0;
        while ((s = fis.read(buf, 0, buf.length)) > 0) {
          t += s;
          blob.write(buf, 0, s);
        }
        break;

      case NATIVE_STREAM:
        os = blob.getOutputStream();
        s = fis.read();
        while (s > -1) {
          os.write(s);
          s = fis.read();
        }
        os.close();
        break;

      default:
        fail("Unknown method in uploadFile");
    }

    blob.close();
    fis.close();

    // Insert into the table
    Statement st = con.createStatement();
    st.executeUpdate(TestUtil.insertSQL("testblob", "id,lo", "'" + file + "'," + oid));
    con.commit();
    st.close();

    return oid;
  }

  /**
   * Creates a large object big enough to require several read buffers, filled so that byte
   * {@code i} of every 1024-byte block equals the block index. This lets the skip tests assert the
   * exact byte they land on.
   *
   * @return the OID of the created large object
   * @see org.postgresql.largeobject.BlobInputStream#INITIAL_BUFFER_SIZE
   */
  private long createMediumLargeObject() throws Exception {
    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();
    long loid = lom.createLO();
    try (LargeObject blob = lom.open(loid, LargeObjectManager.WRITE)) {
      byte[] buf = new byte[1024];
      for (byte i = 0; i < 96; i++) {
        Arrays.fill(buf, i);
        blob.write(buf);
      }
    }
    return loid;
  }

  /*
   * Helper - compares the blobs in a table with a local file. Note this uses the postgresql
   * specific Large Object API
   */
  private boolean compareBlobsLOAPI(String id) throws Exception {
    boolean result = true;

    LargeObjectManager lom = ((PGConnection) con).getLargeObjectAPI();

    try (Statement st = con.createStatement()) {
      try (ResultSet rs = st.executeQuery(TestUtil.selectSQL("testblob", "id,lo", "id = '" + id + "'"))) {
        assertNotNull(rs);

        while (rs.next()) {
          String file = rs.getString(1);
          long oid = rs.getLong(2);

          InputStream fis = getClass().getResourceAsStream(file);
          LargeObject blob = lom.open(oid);
          InputStream bis = blob.getInputStream();

          int f = fis.read();
          int b = bis.read();
          int c = 0;
          while (f >= 0 && b >= 0 & result) {
            result = f == b;
            f = fis.read();
            b = bis.read();
            c++;
          }
          result = result && f == -1 && b == -1;

          if (!result) {
            fail("Large Object API Blob compare failed at " + c + " of " + blob.size());
          }

          blob.close();
          fis.close();
        }
      }
    }
    return result;
  }

  /*
   * Helper - compares the blobs in a table with a local file. This uses the jdbc java.sql.Blob api
   */
  private boolean compareBlobs(String id) throws Exception {
    boolean result = true;

    try (Statement st = con.createStatement()) {
      try (ResultSet rs = st.executeQuery(TestUtil.selectSQL("testblob", "id,lo", "id = '" + id + "'"))) {
        assertNotNull(rs);

        while (rs.next()) {
          String file = rs.getString(1);
          Blob blob = rs.getBlob(2);

          InputStream fis = getClass().getResourceAsStream(file);
          InputStream bis = blob.getBinaryStream();

          int f = fis.read();
          int b = bis.read();
          int c = 0;
          while (f >= 0 && b >= 0 & result) {
            result = f == b;
            f = fis.read();
            b = bis.read();
            c++;
          }
          result = result && f == -1 && b == -1;

          if (!result) {
            fail("JDBC API Blob compare failed at " + c + " of " + blob.length());
          }

          bis.close();
          fis.close();
        }
      }
    }
    return result;
  }

  /*
   * Helper - compares the clobs in a table with a local file.
   */
  private boolean compareClobs(String id) throws Exception {
    boolean result = true;

    try (Statement st = con.createStatement()) {
      try (ResultSet rs = st.executeQuery(TestUtil.selectSQL("testblob", "id,lo", "id = '" + id + "'"))) {
        assertNotNull(rs);

        while (rs.next()) {
          String file = rs.getString(1);
          Clob clob = rs.getClob(2);

          InputStream fis = getClass().getResourceAsStream(file);
          InputStream bis = clob.getAsciiStream();

          int f = fis.read();
          int b = bis.read();
          int c = 0;
          while (f >= 0 && b >= 0 & result) {
            result = f == b;
            f = fis.read();
            b = bis.read();
            c++;
          }
          result = result && f == -1 && b == -1;

          if (!result) {
            fail("Clob compare failed at " + c + " of " + clob.length());
          }

          bis.close();
          fis.close();
        }
      }
    }

    return result;
  }
}
