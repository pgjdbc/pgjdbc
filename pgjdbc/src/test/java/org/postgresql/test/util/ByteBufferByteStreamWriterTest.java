/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.util.ByteBufferByteStreamWriter;
import org.postgresql.util.ByteStreamWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

class ByteBufferByteStreamWriterTest {

  private ByteArrayOutputStream targetStream;
  private byte[] data;
  private ByteBufferByteStreamWriter writer;

  @BeforeEach
  void setUp() throws Exception {
    targetStream = new ByteArrayOutputStream();
    data = new byte[]{1, 2, 3, 4};
    ByteBuffer buffer = ByteBuffer.wrap(data);
    writer = new ByteBufferByteStreamWriter(buffer);
  }

  @Test
  void reportsLengthCorrectly() {
    assertEquals(4, writer.getLength(), "Incorrect length reported");
  }

  @Test
  void copiesDataCorrectly() throws IOException {
    writer.writeTo(target(targetStream));
    byte[] written = targetStream.toByteArray();
    assertArrayEquals(data, written, "Incorrect data written to target stream");
  }

  @Test
  void propagatesException() throws IOException {
    final IOException e = new IOException("oh no");
    OutputStream errorStream = new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        throw e;
      }
    };
    try {
      writer.writeTo(target(errorStream));
      fail("No exception thrown");
    } catch (IOException caught) {
      assertEquals(caught, e, "Exception was thrown that wasn't the expected one");
    }
  }

  private static ByteStreamWriter.ByteStreamTarget target(final OutputStream stream) {
    return new ByteStreamWriter.ByteStreamTarget() {
      @Override
      public OutputStream getOutputStream() {
        return stream;
      }
    };
  }
}
