/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyInputStream;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class PGCopyInputStreamTest {
  private static final int NUM_TEST_ROWS = 4;
  /**
   * COPY .. TO STDOUT terminates each row of data with a LF regardless of platform so the size of
   * each output row will always be two, one byte for the character and one for the LF.
   */
  private static final int COPY_ROW_SIZE = 2; // One character plus newline
  private static final int COPY_DATA_SIZE = NUM_TEST_ROWS * COPY_ROW_SIZE;
  private static final String COPY_SQL = String.format("COPY (SELECT i FROM generate_series(0, %d - 1) i) TO STDOUT", NUM_TEST_ROWS);

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    conn = TestUtil.openDB();
  }

  @After
  public void tearDown() throws SQLException {
    TestUtil.closeDB(conn);
  }

  @Test
  public void testReadBytesCorrectlyHandlesEof() throws SQLException, IOException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);
    try (PGCopyInputStream in = new PGCopyInputStream(pgConn, COPY_SQL)) {
      // large enough to read everything on the next step
      byte[] buf = new byte[COPY_DATA_SIZE + 100];
      assertEquals("First read should get the entire table into the byte array",
          COPY_DATA_SIZE, in.read(buf));
      assertEquals("Subsequent read should return -1 to indicate stream is finished",
          -1, in.read(buf));
    }
  }

  @Test
  public void testReadBytesCorrectlyReadsDataInChunks() throws SQLException, IOException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);
    try (PGCopyInputStream in = new PGCopyInputStream(pgConn, COPY_SQL)) {
      // Read in row sized chunks
      List<byte[]> chunks = readFully(in, COPY_ROW_SIZE);
      assertEquals("Should read one chunk per row", NUM_TEST_ROWS, chunks.size());
      assertEquals("Entire table should have be read", "0\n1\n2\n3\n", chunksToString(chunks));
    }
  }

  @Test
  public void testCopyAPI() throws SQLException, IOException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);
    try (PGCopyInputStream in = new PGCopyInputStream(pgConn, COPY_SQL)) {
      List<byte[]> chunks = readFromCopyFully(in);
      assertEquals("Should read one chunk per row", NUM_TEST_ROWS, chunks.size());
      assertEquals("Entire table should have be read", "0\n1\n2\n3\n", chunksToString(chunks));
    }
  }

  @Test
  public void testMixedAPI() throws SQLException, IOException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);
    try (PGCopyInputStream in = new PGCopyInputStream(pgConn, COPY_SQL)) {
      // First read using java.io.InputStream API
      byte[] firstChar = new byte[1];
      in.read(firstChar);
      assertArrayEquals("IO API should read first character", "0".getBytes(), firstChar);

      // Read remainder of first row using CopyOut API
      assertArrayEquals("readFromCopy() should return remainder of first row", "\n".getBytes(), in.readFromCopy());

      // Then read the rest using CopyOut API
      List<byte[]> chunks = readFromCopyFully(in);
      assertEquals("Should read one chunk per row", NUM_TEST_ROWS - 1, chunks.size());
      assertEquals("Rest of table should have be read", "1\n2\n3\n", chunksToString(chunks));
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
