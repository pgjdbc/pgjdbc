package org.postgresql.test.jdbc4;

import org.postgresql.test.TestUtil;

import junit.framework.TestCase;
import org.junit.Assert;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Random;

public class BinaryStreamTest extends TestCase {

  private Connection _conn;

  private ByteBuffer _testData;

  protected void setUp() throws Exception {
    _conn = TestUtil.openDB();
    TestUtil.createTable(_conn, "images", "img bytea");

    Random random = new Random(31459);
    _testData = ByteBuffer.allocate(200 * 1024);
    while (_testData.remaining() > 0) {
      _testData.putLong(random.nextLong());
    }
  }

  protected void tearDown() throws SQLException {
    TestUtil.dropTable(_conn, "images");
    TestUtil.closeDB(_conn);
  }

  private void insertStreamKownLength(byte[] data)
      throws Exception {
    PreparedStatement updatePS = _conn.prepareStatement(TestUtil.insertSQL("images", "img", "?"));
    try {
      updatePS.setBinaryStream(1, new ByteArrayInputStream(data), data.length);
      updatePS.executeUpdate();
    } finally {
      updatePS.close();
    }
  }

  private void insertStreamUnkownLength(byte[] data)
      throws Exception {
    PreparedStatement updatePS = _conn.prepareStatement(TestUtil.insertSQL("images", "img", "?"));
    try {
      updatePS.setBinaryStream(1, new ByteArrayInputStream(data));
      updatePS.executeUpdate();
    } finally {
      updatePS.close();
    }
  }

  private void validateContent(byte[] data)
      throws Exception {
    PreparedStatement selectPS = _conn.prepareStatement(TestUtil.selectSQL("images", "img"));
    try {
      ResultSet rs = selectPS.executeQuery();
      try {
        rs.next();
        byte[] actualData = rs.getBytes(1);
        Assert.assertArrayEquals("Sent and received data are not the same", data, actualData);
      } finally {
        rs.close();
      }
    } finally {
      selectPS.close();
    }

    PreparedStatement deletePS = _conn.prepareStatement("DELETE FROM images");
    try {
      deletePS.executeUpdate();
    } finally {
      deletePS.close();
    }
  }

  private byte[] getTestData(int size) {
    _testData.rewind();
    byte[] data = new byte[size];
    _testData.get(data);
    return data;
  }

  public void testKnownLengthEmpty() throws Exception {
    byte[] data = new byte[0];
    insertStreamKownLength(data);
    validateContent(data);
  }

  public void testKnownLength2Kb() throws Exception {
    byte[] data = getTestData(2 * 1024);
    insertStreamKownLength(data);
    validateContent(data);
  }

  public void testKnownLength10Kb() throws Exception {
    byte[] data = getTestData(10 * 1024);
    insertStreamKownLength(data);
    validateContent(data);
  }

  public void testKnownLength100Kb() throws Exception {
    byte[] data = getTestData(100 * 1024);
    insertStreamKownLength(data);
    validateContent(data);
  }

  public void testKnownLength200Kb() throws Exception {
    byte[] data = getTestData(200 * 1024);
    insertStreamKownLength(data);
    validateContent(data);
  }

  public void testUnknownLengthEmpty() throws Exception {
    byte[] data = getTestData(2 * 1024);
    insertStreamUnkownLength(data);
    validateContent(data);
  }

  public void testUnknownLength2Kb() throws Exception {
    byte[] data = getTestData(2 * 1024);
    insertStreamUnkownLength(data);
    validateContent(data);
  }

  public void testUnknownLength10Kb() throws Exception {
    byte[] data = getTestData(10 * 1024);
    insertStreamUnkownLength(data);
    validateContent(data);
  }

  public void testUnknownLength100Kb() throws Exception {
    byte[] data = getTestData(100 * 1024);
    insertStreamUnkownLength(data);
    validateContent(data);
  }

  public void testUnknownLength200Kb() throws Exception {
    byte[] data = getTestData(200 * 1024);
    insertStreamUnkownLength(data);
    validateContent(data);
  }
}
