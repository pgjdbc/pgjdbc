/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * This test-case is only for JDBC4 blob methods. Take a look at
 * {@link org.postgresql.test.jdbc2.BlobTest} for base tests concerning blobs
 */
class BlobTest {

  private Connection conn;

  @BeforeEach
  void setUp() throws Exception {
    conn = TestUtil.openDB();
    TestUtil.createTable(conn, "testblob", "id name,lo oid");
    conn.setAutoCommit(false);
  }

  @AfterEach
  void tearDown() throws Exception {
    conn.setAutoCommit(true);
    try {
      Statement stmt = conn.createStatement();
      try {
        stmt.execute("SELECT lo_unlink(lo) FROM testblob");
      } finally {
        try {
          stmt.close();
        } catch (Exception e) {
        }
      }
    } finally {
      TestUtil.dropTable(conn, "testblob");
      TestUtil.closeDB(conn);
    }
  }

  @Test
  void setBlobWithStream() throws Exception {
    byte[] data = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Pellentesque bibendum dapibus varius."
        .getBytes("UTF-8");
    try ( PreparedStatement insertPS = conn.prepareStatement(TestUtil.insertSQL("testblob", "lo", "?")) ) {
      insertPS.setBlob(1, new ByteArrayInputStream(data));
      insertPS.executeUpdate();
    }

    try (Statement selectStmt = conn.createStatement() ) {
      try (ResultSet rs = selectStmt.executeQuery(TestUtil.selectSQL("testblob", "lo"))) {
        assertTrue(rs.next());

        Blob actualBlob = rs.getBlob(1);
        byte[] actualBytes = actualBlob.getBytes(1, (int) actualBlob.length());

        assertArrayEquals(data, actualBytes);
      }
    }
  }

  @Test
  void setBlobWithStreamAndLength() throws Exception {
    byte[] fullData = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Suspendisse placerat tristique tellus, id tempus lectus."
            .getBytes("UTF-8");
    byte[] data =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit.".getBytes("UTF-8");

    try ( PreparedStatement insertPS = conn.prepareStatement(TestUtil.insertSQL("testblob", "lo", "?")) ) {
      insertPS.setBlob(1, new ByteArrayInputStream(fullData), data.length);
      insertPS.executeUpdate();
    }

    try ( Statement selectStmt = conn.createStatement() ) {
      try (ResultSet rs = selectStmt.executeQuery(TestUtil.selectSQL("testblob", "lo"))) {
        assertTrue(rs.next());

        Blob actualBlob = rs.getBlob(1);
        byte[] actualBytes = actualBlob.getBytes(1, (int) actualBlob.length());

        assertArrayEquals(data, actualBytes);
      }
    }
  }

  @Test
  void getBinaryStreamWithBoundaries() throws Exception {
    byte[] data =
        "Cras vestibulum tellus eu sapien imperdiet ornare.".getBytes("UTF-8");
    try ( PreparedStatement insertPS = conn.prepareStatement(TestUtil.insertSQL("testblob", "lo", "?")) ) {
      insertPS.setBlob(1, new ByteArrayInputStream(data), data.length);
      insertPS.executeUpdate();
    }
    try ( Statement selectStmt = conn.createStatement() ) {
      try (ResultSet rs = selectStmt.executeQuery(TestUtil.selectSQL("testblob", "lo"))) {
        assertTrue(rs.next());

        byte[] actualData = new byte[10];
        Blob actualBlob = rs.getBlob(1);
        InputStream stream = actualBlob.getBinaryStream(6, 10);
        try {
          stream.read(actualData);
          assertEquals(-1, stream.read(new byte[1]), "Stream should be at end");
        } finally {
          stream.close();
        }
        assertEquals("vestibulum", new String(actualData, "UTF-8"));
      }
    }
  }

  @Test
  void getBinaryStreamWithBoundaries2() throws Exception {
    byte[] data =
        "Cras vestibulum tellus eu sapien imperdiet ornare.".getBytes("UTF-8");

    try ( PreparedStatement insertPS = conn.prepareStatement(TestUtil.insertSQL("testblob", "lo", "?")) ) {
      insertPS.setBlob(1, new ByteArrayInputStream(data), data.length);
      insertPS.executeUpdate();
    }

    try ( Statement selectStmt = conn.createStatement() ) {
      try (ResultSet rs = selectStmt.executeQuery(TestUtil.selectSQL("testblob", "lo"))) {
        assertTrue(rs.next());

        byte[] actualData = new byte[9];
        Blob actualBlob = rs.getBlob(1);
        try ( InputStream stream = actualBlob.getBinaryStream(6, 10) ) {
          // read 9 bytes 1 at a time
          for (int i = 0; i < 9; i++) {
            actualData[i] = (byte) stream.read();
          }
          /* try to read past the end and make sure we get 1 byte */
          assertEquals(1, stream.read(new byte[2]), "There should be 1 byte left");
          /* now read one more and we should get an EOF */
          assertEquals(-1, stream.read(new byte[1]), "Stream should be at end");
        }
        assertEquals("vestibulu", new String(actualData, "UTF-8"));
      }
    }
  }

  @Test
  void free() throws SQLException {
    try ( Statement stmt = conn.createStatement() ) {
      stmt.execute("INSERT INTO testblob(lo) VALUES(lo_creat(-1))");
      try (ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob")) {
        assertTrue(rs.next());

        Blob blob = rs.getBlob(1);
        blob.free();
        try {
          blob.length();
          fail("Should have thrown an Exception because it was freed.");
        } catch (SQLException sqle) {
          // expected
        }
      }
    }
  }
}
