/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.test.SlowTests;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.ByteBufferByteStreamWriter;
import org.postgresql.util.ByteStreamWriter;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Random;

public class ByteStreamWriterTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    assumeByteaSupported();
    TestUtil.createTempTable(con, "images", "img bytea");
  }

  private ByteBuffer testData(int size) {
    ByteBuffer data = ByteBuffer.allocate(size);
    Random random = new Random(31459);
    while (data.remaining() > 0) {
      data.putLong(random.nextLong());
    }
    data.rewind();
    return data;
  }

  private void insertStream(ByteBuffer testData) throws Exception {
    insertStream(testData, null);
  }

  private void insertStream(ByteBuffer testData, Integer lengthOverride) throws Exception {
    insertStream(new TestByteBufferByteStreamWriter(testData, lengthOverride));
  }

  private void insertStream(ByteStreamWriter writer) throws Exception {
    PreparedStatement updatePS = con.prepareStatement(TestUtil.insertSQL("images", "img", "?"));
    try {
      updatePS.setObject(1, writer);
      updatePS.executeUpdate();
    } finally {
      updatePS.close();
    }
  }

  private void validateContent(ByteBuffer data) throws Exception {
    validateContent(data.array());
  }

  private void validateContent(byte [] data) throws Exception {
    PreparedStatement selectPS = con.prepareStatement(TestUtil.selectSQL("images", "img"));
    try {
      ResultSet rs = selectPS.executeQuery();
      try {
        rs.next();
        byte[] actualData = rs.getBytes(1);
        assertArrayEquals("Sent and received data are not the same", data, actualData);
      } finally {
        rs.close();
      }
    } finally {
      selectPS.close();
    }
  }

  @Test
  public void testEmpty() throws Exception {
    ByteBuffer testData = testData(0);
    insertStream(testData);
    validateContent(testData);
  }

  @Test
  public void testLength2Kb() throws Exception {
    ByteBuffer testData = testData(2 * 1024);
    insertStream(testData);
    validateContent(testData);
  }

  @Test
  public void testLength10Kb() throws Exception {
    ByteBuffer testData = testData(10 * 1024);
    insertStream(testData);
    validateContent(testData);
  }

  @Test
  @Category(SlowTests.class)
  public void testLength100Kb() throws Exception {
    ByteBuffer testData = testData(100 * 1024);
    insertStream(testData);
    validateContent(testData);
  }

  @Test
  @Category(SlowTests.class)
  public void testLength200Kb() throws Exception {
    ByteBuffer testData = testData(200 * 1024);
    insertStream(testData);
    validateContent(testData);
  }

  @Test
  public void testLengthGreaterThanContent() throws Exception {
    ByteBuffer testData = testData(8);
    insertStream(testData, 10);
    byte[] expectedData = new byte[10];
    testData.rewind();
    testData.get(expectedData, 0, 8);
    // other two bytes are zeroed out, which the jvm does for us automatically
    validateContent(expectedData);
  }

  @Test
  public void testLengthLessThanContent() throws Exception {
    ByteBuffer testData = testData(8);
    try {
      insertStream(testData, 4);
      fail("did not throw exception when too much content");
    } catch (SQLException e) {
      Throwable cause = e.getCause();
      assertTrue("cause wan't an IOException", cause instanceof IOException);
      assertEquals("Incorrect exception message",
          cause.getMessage(), "Attempt to write more than the specified 4 bytes");
    }
  }

  @Test
  public void testIOExceptionPassedThroughAsCause() throws Exception {
    IOException e = new IOException("oh no");
    try {
      insertStream(new ExceptionThrowingByteStreamWriter(e));
      fail("did not throw exception when IOException thrown");
    } catch (SQLException sqle) {
      Throwable cause = sqle.getCause();
      assertEquals("Incorrect exception cause", e, cause);
    }
  }

  @Test
  public void testRuntimeExceptionPassedThroughAsIOException() throws Exception {
    RuntimeException e = new RuntimeException("oh no");
    try {
      insertStream(new ExceptionThrowingByteStreamWriter(e));
      fail("did not throw exception when RuntimeException thrown");
    } catch (SQLException sqle) {
      Throwable cause = sqle.getCause();
      assertTrue("cause wan't an IOException", cause instanceof IOException);
      assertEquals("Incorrect exception message",
          cause.getMessage(), "Error writing bytes to stream");
      Throwable nestedCause = cause.getCause();
      assertEquals("Incorrect exception cause", e, nestedCause);
    }
  }

  /**
   * Allows testing where reported length doesn't match what the stream writer attempts
   */
  private static class TestByteBufferByteStreamWriter extends ByteBufferByteStreamWriter {

    private final Integer lengthOverride;

    private TestByteBufferByteStreamWriter(ByteBuffer buf, Integer lengthOverride) {
      super(buf);
      this.lengthOverride = lengthOverride;
    }

    @Override
    public int getLength() {
      return lengthOverride != null ? lengthOverride : super.getLength();
    }
  }

  private static class ExceptionThrowingByteStreamWriter implements ByteStreamWriter {

    private final Throwable cause;

    private ExceptionThrowingByteStreamWriter(Throwable cause) {
      assertTrue(cause instanceof RuntimeException || cause instanceof IOException);
      this.cause = cause;
    }

    @Override
    public int getLength() {
      return 1;
    }

    @Override
    public void writeTo(ByteStreamTarget target) throws IOException {
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      } else if (cause instanceof IOException) {
        throw (IOException) cause;
      }
    }
  }

}
