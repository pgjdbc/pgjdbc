/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import org.postgresql.test.SlowTests;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Random;

public class BinaryStreamTest extends BaseTest4 {

  private ByteBuffer testData;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    assumeByteaSupported();
    TestUtil.createTable(con, "images", "img bytea");

    Random random = new Random(31459);
    testData = ByteBuffer.allocate(200 * 1024);
    while (testData.remaining() > 0) {
      testData.putLong(random.nextLong());
    }
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "images");
    super.tearDown();
  }

  private void insertStreamKownLength(byte[] data) throws Exception {
    PreparedStatement updatePS = con.prepareStatement(TestUtil.insertSQL("images", "img", "?"));
    try {
      updatePS.setBinaryStream(1, new ByteArrayInputStream(data), data.length);
      updatePS.executeUpdate();
    } finally {
      updatePS.close();
    }
  }

  private void insertStreamUnkownLength(byte[] data) throws Exception {
    PreparedStatement updatePS = con.prepareStatement(TestUtil.insertSQL("images", "img", "?"));
    try {
      updatePS.setBinaryStream(1, new ByteArrayInputStream(data));
      updatePS.executeUpdate();
    } finally {
      updatePS.close();
    }
  }

  private void validateContent(byte[] data) throws Exception {
    PreparedStatement selectPS = con.prepareStatement(TestUtil.selectSQL("images", "img"));
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

    PreparedStatement deletePS = con.prepareStatement("DELETE FROM images");
    try {
      deletePS.executeUpdate();
    } finally {
      deletePS.close();
    }
  }

  private byte[] getTestData(int size) {
    testData.rewind();
    byte[] data = new byte[size];
    testData.get(data);
    return data;
  }

  @Test
  public void testKnownLengthEmpty() throws Exception {
    byte[] data = new byte[0];
    insertStreamKownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownLength2Kb() throws Exception {
    byte[] data = getTestData(2 * 1024);
    insertStreamKownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownLength10Kb() throws Exception {
    byte[] data = getTestData(10 * 1024);
    insertStreamKownLength(data);
    validateContent(data);
  }

  @Test
  @Category(SlowTests.class)
  public void testKnownLength100Kb() throws Exception {
    byte[] data = getTestData(100 * 1024);
    insertStreamKownLength(data);
    validateContent(data);
  }

  @Test
  @Category(SlowTests.class)
  public void testKnownLength200Kb() throws Exception {
    byte[] data = getTestData(200 * 1024);
    insertStreamKownLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLengthEmpty() throws Exception {
    byte[] data = getTestData(2 * 1024);
    insertStreamUnkownLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLength2Kb() throws Exception {
    byte[] data = getTestData(2 * 1024);
    insertStreamUnkownLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLength10Kb() throws Exception {
    byte[] data = getTestData(10 * 1024);
    insertStreamUnkownLength(data);
    validateContent(data);
  }

  @Test
  @Category(SlowTests.class)
  public void testUnknownLength100Kb() throws Exception {
    byte[] data = getTestData(100 * 1024);
    insertStreamUnkownLength(data);
    validateContent(data);
  }

  @Test
  @Category(SlowTests.class)
  public void testUnknownLength200Kb() throws Exception {
    byte[] data = getTestData(200 * 1024);
    insertStreamUnkownLength(data);
    validateContent(data);
  }
}
