package org.postgresql.test.jdbc4;

import org.postgresql.test.TestUtil;

import junit.framework.TestCase;
import org.junit.Assert;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * This test-case is only for JDBC4 blob methods. Take a look at {@link
 * org.postgresql.test.jdbc2.BlobTest} for base tests concerning blobs
 */
public class BlobTest extends TestCase {

  private Connection _conn;

  protected void setUp() throws Exception {
    _conn = TestUtil.openDB();
    TestUtil.createTable(_conn, "testblob", "id name,lo oid");
    _conn.setAutoCommit(false);
  }

  protected void tearDown() throws Exception {
    _conn.setAutoCommit(true);
    try {
      Statement stmt = _conn.createStatement();
      try {
        stmt.execute("SELECT lo_unlink(lo) FROM testblob");
      } finally {
        try {
          stmt.close();
        } catch (Exception e) {
        }
      }
    } finally {
      TestUtil.dropTable(_conn, "testblob");
      TestUtil.closeDB(_conn);
    }
  }

  public void testSetBlobWithStream() throws Exception {
    byte[] data = new String(
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Pellentesque bibendum dapibus varius.")
        .getBytes("UTF-8");
    PreparedStatement insertPS = _conn.prepareStatement(TestUtil.insertSQL("testblob", "lo", "?"));
    try {
      insertPS.setBlob(1, new ByteArrayInputStream(data));
      insertPS.executeUpdate();
    } finally {
      insertPS.close();
    }

    Statement selectStmt = _conn.createStatement();
    try {
      ResultSet rs = selectStmt.executeQuery(TestUtil.selectSQL("testblob", "lo"));
      assertTrue(rs.next());

      Blob actualBlob = rs.getBlob(1);
      byte[] actualBytes = actualBlob.getBytes(1, (int) actualBlob.length());

      Assert.assertArrayEquals(data, actualBytes);
    } finally {
      selectStmt.close();
    }
  }

  public void testSetBlobWithStreamAndLength() throws Exception {
    byte[] fullData = new String(
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Suspendisse placerat tristique tellus, id tempus lectus.")
        .getBytes("UTF-8");
    byte[] data =
        new String("Lorem ipsum dolor sit amet, consectetur adipiscing elit.").getBytes("UTF-8");
    PreparedStatement insertPS = _conn.prepareStatement(TestUtil.insertSQL("testblob", "lo", "?"));
    try {
      insertPS.setBlob(1, new ByteArrayInputStream(fullData), data.length);
      insertPS.executeUpdate();
    } finally {
      insertPS.close();
    }

    Statement selectStmt = _conn.createStatement();
    try {
      ResultSet rs = selectStmt.executeQuery(TestUtil.selectSQL("testblob", "lo"));
      assertTrue(rs.next());

      Blob actualBlob = rs.getBlob(1);
      byte[] actualBytes = actualBlob.getBytes(1, (int) actualBlob.length());

      Assert.assertArrayEquals(data, actualBytes);
    } finally {
      selectStmt.close();
    }
  }

  public void testGetBinaryStreamWithBoundaries() throws Exception {
    byte[] data =
        new String("Cras vestibulum tellus eu sapien imperdiet ornare.").getBytes("UTF-8");
    PreparedStatement insertPS = _conn.prepareStatement(TestUtil.insertSQL("testblob", "lo", "?"));
    try {
      insertPS.setBlob(1, new ByteArrayInputStream(data), data.length);
      insertPS.executeUpdate();
    } finally {
      insertPS.close();
    }

    Statement selectStmt = _conn.createStatement();
    try {
      ResultSet rs = selectStmt.executeQuery(TestUtil.selectSQL("testblob", "lo"));
      assertTrue(rs.next());

      byte[] actualData = new byte[10];
      Blob actualBlob = rs.getBlob(1);
      InputStream stream = actualBlob.getBinaryStream(6, 10);
      try {
        stream.read(actualData);
        Assert.assertEquals("Stream should be at end", -1, stream.read(new byte[1]));
      } finally {
        stream.close();
      }
      Assert.assertEquals("vestibulum", new String(actualData, "UTF-8"));
    } finally {
      selectStmt.close();
    }
  }

  public void testFree() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.execute("INSERT INTO testblob(lo) VALUES(lo_creat(-1))");
    ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob");
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
