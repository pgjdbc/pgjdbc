/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class Jdbc3BlobTest {
  private static final String TABLE = "blobtest";
  private static final String INSERT = "INSERT INTO " + TABLE + " VALUES (1, lo_creat(-1))";
  private static final String SELECT = "SELECT ID, DATA FROM " + TABLE + " WHERE ID = 1";

  private Connection _conn;

  @Before
  public void setUp() throws Exception {
    _conn = TestUtil.openDB();
    TestUtil.createTable(_conn, TABLE, "ID INT PRIMARY KEY, DATA OID");
    _conn.setAutoCommit(false);
  }

  @After
  public void tearDown() throws SQLException {
    _conn.setAutoCommit(true);
    try {
      Statement stmt = _conn.createStatement();
      try {
        stmt.execute("SELECT lo_unlink(DATA) FROM " + TABLE);
      } finally {
        try {
          stmt.close();
        } catch (Exception e) {
        }
      }
    } finally {
      TestUtil.dropTable(_conn, TABLE);
      TestUtil.closeDB(_conn);
    }
  }

  /**
   * Test the writing and reading of a single byte.
   */
  @Test
  public void test1Byte() throws SQLException {
    byte[] data = {(byte) 'a'};
    readWrite(data);
  }

  /**
   * Test the writing and reading of a few bytes.
   */
  @Test
  public void testManyBytes() throws SQLException {
    byte[] data = "aaaaaaaaaa".getBytes();
    readWrite(data);
  }

  /**
   * Test writing a single byte with an offset.
   */
  @Test
  public void test1ByteOffset() throws SQLException {
    byte[] data = {(byte) 'a'};
    readWrite(10, data);
  }

  /**
   * Test the writing and reading of a few bytes with an offset.
   */
  @Test
  public void testManyBytesOffset() throws SQLException {
    byte[] data = "aaaaaaaaaa".getBytes();
    readWrite(10, data);
  }

  /**
   * Tests all of the byte values from 0 - 255.
   */
  @Test
  public void testAllBytes() throws SQLException {
    byte[] data = new byte[256];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) i;
    }
    readWrite(data);
  }

  @Test
  public void testTruncate() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v8_3)) {
      return;
    }

    byte[] data = new byte[100];
    for (byte i = 0; i < data.length; i++) {
      data[i] = i;
    }
    readWrite(data);

    PreparedStatement ps = _conn.prepareStatement(SELECT);
    ResultSet rs = ps.executeQuery();

    assertTrue(rs.next());
    Blob blob = rs.getBlob("DATA");

    assertEquals(100, blob.length());

    blob.truncate(50);
    assertEquals(50, blob.length());

    blob.truncate(150);
    assertEquals(150, blob.length());

    data = blob.getBytes(1, 200);
    assertEquals(150, data.length);
    for (byte i = 0; i < 50; i++) {
      assertEquals(i, data[i]);
    }

    for (int i = 50; i < 150; i++) {
      assertEquals(0, data[i]);
    }
  }

  /**
   *
   * @param data data to write
   * @throws SQLException if something goes wrong
   */
  public void readWrite(byte[] data) throws SQLException {
    readWrite(1, data);
  }

  /**
   *
   * @param offset data offset
   * @param data data to write
   * @throws SQLException if something goes wrong
   */
  public void readWrite(int offset, byte[] data) throws SQLException {
    PreparedStatement ps = _conn.prepareStatement(INSERT);
    ps.executeUpdate();
    ps.close();

    ps = _conn.prepareStatement(SELECT);
    ResultSet rs = ps.executeQuery();

    assertTrue(rs.next());
    Blob b = rs.getBlob("DATA");
    b.setBytes(offset, data);

    rs.close();
    ps.close();

    ps = _conn.prepareStatement(SELECT);
    rs = ps.executeQuery();

    assertTrue(rs.next());
    b = rs.getBlob("DATA");
    byte[] rspData = b.getBytes(offset, data.length);
    assertArrayEquals("Request should be the same as the response", data, rspData);

    rs.close();
    ps.close();
  }


  /**
   * Test the writing and reading of a single byte.
   */
  @Test
  public void test1ByteStream() throws SQLException, IOException {
    byte[] data = {(byte) 'a'};
    readWriteStream(data);
  }

  /**
   * Test the writing and reading of a few bytes.
   */
  @Test
  public void testManyBytesStream() throws SQLException, IOException {
    byte[] data = "aaaaaaaaaa".getBytes();
    readWriteStream(data);
  }

  /**
   * Test writing a single byte with an offset.
   */
  @Test
  public void test1ByteOffsetStream() throws SQLException, IOException {
    byte[] data = {(byte) 'a'};
    readWriteStream(10, data);
  }

  /**
   * Test the writing and reading of a few bytes with an offset.
   */
  @Test
  public void testManyBytesOffsetStream() throws SQLException, IOException {
    byte[] data = "aaaaaaaaaa".getBytes();
    readWriteStream(10, data);
  }

  /**
   * Tests all of the byte values from 0 - 255.
   */
  @Test
  public void testAllBytesStream() throws SQLException, IOException {
    byte[] data = new byte[256];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) i;
    }
    readWriteStream(data);
  }

  public void readWriteStream(byte[] data) throws SQLException, IOException {
    readWriteStream(1, data);
  }


  /**
   * Reads then writes data to the blob via a stream.
   */
  public void readWriteStream(int offset, byte[] data) throws SQLException, IOException {
    PreparedStatement ps = _conn.prepareStatement(INSERT);
    ps.executeUpdate();
    ps.close();

    ps = _conn.prepareStatement(SELECT);
    ResultSet rs = ps.executeQuery();

    assertTrue(rs.next());
    Blob b = rs.getBlob("DATA");
    OutputStream out = b.setBinaryStream(offset);
    out.write(data);
    out.flush();
    out.close();

    rs.close();
    ps.close();

    ps = _conn.prepareStatement(SELECT);
    rs = ps.executeQuery();

    assertTrue(rs.next());
    b = rs.getBlob("DATA");
    InputStream in = b.getBinaryStream();
    byte[] rspData = new byte[data.length];
    in.skip(offset - 1);
    in.read(rspData);
    in.close();

    assertArrayEquals("Request should be the same as the response", data, rspData);

    rs.close();
    ps.close();
  }

  @Test
  public void testPattern() throws SQLException {
    byte[] data = "abcdefghijklmnopqrstuvwxyx0123456789".getBytes();
    byte[] pattern = "def".getBytes();

    PreparedStatement ps = _conn.prepareStatement(INSERT);
    ps.executeUpdate();
    ps.close();

    ps = _conn.prepareStatement(SELECT);
    ResultSet rs = ps.executeQuery();

    assertTrue(rs.next());
    Blob b = rs.getBlob("DATA");
    b.setBytes(1, data);

    rs.close();
    ps.close();

    ps = _conn.prepareStatement(SELECT);
    rs = ps.executeQuery();

    assertTrue(rs.next());
    b = rs.getBlob("DATA");
    long position = b.position(pattern, 1);
    byte[] rspData = b.getBytes(position, pattern.length);
    assertArrayEquals("Request should be the same as the response", pattern, rspData);

    rs.close();
    ps.close();
  }
}
