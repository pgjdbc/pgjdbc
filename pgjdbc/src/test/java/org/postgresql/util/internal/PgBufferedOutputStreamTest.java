/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.test.util.StrangeOutputStream;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class PgBufferedOutputStreamTest {
  static class AssertBufferedWrites extends FilterOutputStream {
    private @Nullable String writesForbidden;
    int position;
    private int expectedWriteSize;

    AssertBufferedWrites(OutputStream out) {
      super(out);
    }

    @Override
    public void write(int b) throws IOException {
      throw new IllegalArgumentException("Unexpected write(" + b + ")");
    }

    public void forbidWrites(String message) {
      writesForbidden = message;
    }

    @Override
    public void write(byte[] b) throws IOException {
      assertWritesAllowed(b.length);
      write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      assertWritesAllowed(len);
      assertEquals(expectedWriteSize, len, "write size");
      out.write(b, off, len);
      position += len;
    }

    private void assertWritesAllowed(int len) {
      if (len == 0) {
        fail("Zero-length writes are not allowed");
      }
      if (writesForbidden != null) {
        throw new IllegalStateException(
            "Writes are forbidden: " + writesForbidden
                + ", got write of " + len + " bytes"
                + " at position " + position);
      }
    }

    public void allowWrites(int size) {
      this.expectedWriteSize = size;
      writesForbidden = null;
    }
  }

  @Nested
  class SingleByteTests {
    @Test
    void writeOneByteDoesNotThrow() throws IOException {
      OutputStream baos = new ByteArrayOutputStream();
      PgBufferedOutputStream buf = new PgBufferedOutputStream(baos, 1024);
      for (int i = 0; i < 1024 * 20; i++) {
        buf.write(i & 0xff);
      }
    }

    @Test
    void bufferFlushedOnlyWhenFull() throws IOException {
      AssertBufferedWrites dst = new AssertBufferedWrites(new ByteArrayOutputStream());
      int bufferSize = 128;
      PgBufferedOutputStream out = new PgBufferedOutputStream(dst, bufferSize);
      for (int i = 0; i < bufferSize * 2; i++) {
        if (i > 0 && (i % bufferSize) == 0) {
          dst.allowWrites(bufferSize);
        } else {
          dst.forbidWrites("Data must be buffered");
        }
        out.write(i & 0xff);
      }
    }
  }

  @Nested
  class Int2Tests {
    @Test
    void writeInt2ByteDoesNotThrow() throws IOException {
      OutputStream baos = new ByteArrayOutputStream();
      PgBufferedOutputStream buf = new PgBufferedOutputStream(baos, 1024);
      for (int i = 0; i < 1024 * 20; i++) {
        buf.writeInt2(i);
      }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void bufferFlushedOnlyWhenFull(int offset) throws IOException {
      AssertBufferedWrites dst = new AssertBufferedWrites(new ByteArrayOutputStream());
      int bufferSize = 128;
      PgBufferedOutputStream out = new PgBufferedOutputStream(dst, bufferSize);
      int bufferPosition = offset;
      out.writeZeros(offset);
      for (int i = 0; i < bufferSize * 2; i++) {
        if (bufferSize - bufferPosition < 2) {
          dst.allowWrites(bufferPosition);
          bufferPosition = 0;
        } else {
          dst.forbidWrites("Data must be buffered");
        }
        out.writeInt2(i & 0xffff);
        bufferPosition += 2;
      }
    }
  }

  @Nested
  class Int4Tests {
    @Test
    void writeInt4ByteDoesNotThrow() throws IOException {
      OutputStream baos = new ByteArrayOutputStream();
      PgBufferedOutputStream buf = new PgBufferedOutputStream(baos, 1024);
      for (int i = 0; i < 1024 * 20; i++) {
        buf.writeInt4(i);
      }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void bufferFlushedOnlyWhenFull(int offset) throws IOException {
      AssertBufferedWrites dst = new AssertBufferedWrites(new ByteArrayOutputStream());
      int bufferSize = 128;
      PgBufferedOutputStream out = new PgBufferedOutputStream(dst, bufferSize);
      int bufferPosition = offset;
      out.writeZeros(offset);
      for (int i = 0; i < bufferSize * 2; i++) {
        if (bufferSize - bufferPosition < 4) {
          dst.allowWrites(bufferPosition);
          bufferPosition = 0;
        } else {
          dst.forbidWrites("Data must be buffered");
        }
        out.writeInt4(i & 0xffff);
        bufferPosition += 4;
      }
    }
  }

  @Test
  void writeAndCompare() throws IOException {
    byte[] data = new byte[1024 * 1024];
    // Use the same data every time for easier debugging
    new Random(0).nextBytes(data);
    Instant deadline = Instant.now().plus(5, ChronoUnit.SECONDS);
    while (Instant.now().isBefore(deadline)) {
      writeAndCompareOne(ThreadLocalRandom.current().nextLong(), data);
    }
  }

  private void writeAndCompareOne(long seed, byte[] data) throws IOException {
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      Random rnd = new Random(seed);
      int bufferSize = rnd.nextInt(16 * 1024) + 1;
      int writeSize = rnd.nextInt(data.length + 1);
      PgBufferedOutputStream buffered = new PgBufferedOutputStream(baos, bufferSize);
      StrangeOutputStream out = new StrangeOutputStream(buffered, seed, 0.1);
      out.write(data, 0, writeSize);
      out.flush();
      byte[] result = baos.toByteArray();
      assertEquals(
          writeSize,
          result.length,
          "The number of bytes produced by PgBufferedOutputStream should match the number"
              + " of the written bytes"
      );
      for (int i = 0; i < writeSize; i++) {
        int pos = i;
        assertEquals(
            data[i],
            result[i],
            () -> "The result of PgBufferedOutputStream should match the input data. "
                + "Mismatch detected at position " + pos + " of " + writeSize
        );
      }
    } catch (Throwable t) {
      String message = "Test seed is " + seed;
      t.addSuppressed(new Throwable(message) {
        @Override
        public Throwable fillInStackTrace() {
          return this;
        }
      });
      throw t;
    }
  }
}
