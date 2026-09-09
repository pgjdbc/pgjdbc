/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyInputStream;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

class PGCopyInputStreamTest {
  private static final int NUM_TEST_ROWS = 4;
  /**
   * COPY .. TO STDOUT terminates each row of data with a LF regardless of platform so the size of
   * each output row will always be two, one byte for the character and one for the LF.
   */
  private static final int COPY_ROW_SIZE = 2; // One character plus newline
  private static final int COPY_DATA_SIZE = NUM_TEST_ROWS * COPY_ROW_SIZE;
  private static final String COPY_SQL = String.format("COPY (SELECT i FROM generate_series(0, %d - 1) i) TO STDOUT", NUM_TEST_ROWS);

  private Connection conn;

  @BeforeEach
  void setUp() throws Exception {
    conn = TestUtil.openDB();
  }

  @AfterEach
  void tearDown() throws SQLException {
    TestUtil.closeDB(conn);
  }

  @Test
  void readBytesCorrectlyHandlesEof() throws SQLException, IOException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);
    try (PGCopyInputStream in = new PGCopyInputStream(pgConn, COPY_SQL)) {
      // large enough to read everything on the next step
      byte[] buf = new byte[COPY_DATA_SIZE + 100];
      assertEquals(COPY_DATA_SIZE, in.read(buf), "First read should get the entire table into the byte array");
      assertEquals(-1, in.read(buf), "Subsequent read should return -1 to indicate stream is finished");
    }
  }

  @Test
  void readBytesCorrectlyReadsDataInChunks() throws SQLException, IOException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);
    try (PGCopyInputStream in = new PGCopyInputStream(pgConn, COPY_SQL)) {
      // Read in row sized chunks
      List<byte[]> chunks = readFully(in, COPY_ROW_SIZE);
      assertEquals(NUM_TEST_ROWS, chunks.size(), "Should read one chunk per row");
      assertEquals("0\n1\n2\n3\n", chunksToString(chunks), "Entire table should have be read");
    }
  }

  @Test
  void copyAPI() throws SQLException, IOException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);
    try (PGCopyInputStream in = new PGCopyInputStream(pgConn, COPY_SQL)) {
      List<byte[]> chunks = readFromCopyFully(in);
      assertEquals(NUM_TEST_ROWS, chunks.size(), "Should read one chunk per row");
      assertEquals("0\n1\n2\n3\n", chunksToString(chunks), "Entire table should have be read");
    }
  }

  @Test
  void mixedAPI() throws SQLException, IOException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);
    try (PGCopyInputStream in = new PGCopyInputStream(pgConn, COPY_SQL)) {
      // First read using java.io.InputStream API
      byte[] firstChar = new byte[1];
      in.read(firstChar);
      assertArrayEquals("0".getBytes(), firstChar, "IO API should read first character");

      // Read remainder of first row using CopyOut API
      assertArrayEquals("\n".getBytes(), in.readFromCopy(), "readFromCopy() should return remainder of first row");

      // Then read the rest using CopyOut API
      List<byte[]> chunks = readFromCopyFully(in);
      assertEquals(NUM_TEST_ROWS - 1, chunks.size(), "Should read one chunk per row");
      assertEquals("1\n2\n3\n", chunksToString(chunks), "Rest of table should have be read");
    }
  }

  @Test
  void connectionIsUsableAfterPartialRead() throws SQLException, IOException {
    // Regression test for https://github.com/pgjdbc/pgjdbc/issues/1290:
    // closing a PGCopyInputStream before EOF must drain remaining server messages
    // so the connection can be reused for a subsequent COPY operation.
    PGConnection pgConn = conn.unwrap(PGConnection.class);

    // Partial read: consume fewer bytes than the full COPY output, then close.
    try (PGCopyInputStream in = new PGCopyInputStream(pgConn, COPY_SQL)) {
      byte[] partial = new byte[COPY_ROW_SIZE];
      assertEquals(COPY_ROW_SIZE, in.read(partial), "Should read one row");
      // close() calls cancelCopy() which must drain remaining CopyData + ReadyForQuery
    }

    // The connection must be usable for a fresh COPY after the partial read + close.
    try (PGCopyInputStream in2 = new PGCopyInputStream(pgConn, COPY_SQL)) {
      List<byte[]> chunks = readFully(in2, COPY_ROW_SIZE);
      assertEquals(NUM_TEST_ROWS, chunks.size(), "Second COPY must return all rows");
      assertEquals("0\n1\n2\n3\n", chunksToString(chunks), "Second COPY must return correct data");
    }
  }

  private static List<byte[]> readFully(PGCopyInputStream in, int size) throws SQLException, IOException {
    List<byte[]> chunks = new ArrayList<>();
    do {
      byte[] buf = new byte[size];
      if (in.read(buf) <= 0) {
        break;
      }
      chunks.add(buf);
    } while (true);
    return chunks;
  }

  private static List<byte[]> readFromCopyFully(PGCopyInputStream in) throws SQLException, IOException {
    List<byte[]> chunks = new ArrayList<>();
    byte[] buf;
    while ((buf = in.readFromCopy()) != null) {
      chunks.add(buf);
    }
    return chunks;
  }

  private static String chunksToString(List<byte[]> chunks) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    chunks.forEach(chunk -> out.write(chunk, 0, chunk.length));
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }
}
