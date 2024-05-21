/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import org.postgresql.test.util.StrangeOutputStream;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

  private static final int ZEROS_BUFFER_SIZE = 16;

  public static Iterable<Arguments> zerosOffsetAndNumber() {
    List<Arguments> res = new ArrayList<>();
    for (int offset : new int[]{
        0, 1, 2, 3,
        ZEROS_BUFFER_SIZE - 3, ZEROS_BUFFER_SIZE - 2, ZEROS_BUFFER_SIZE - 1,
        ZEROS_BUFFER_SIZE,
        ZEROS_BUFFER_SIZE + 1, ZEROS_BUFFER_SIZE + 2, ZEROS_BUFFER_SIZE + 3}) {
      for (int numZeros : new int[]{
          1, 2, 3,
          ZEROS_BUFFER_SIZE - 2, ZEROS_BUFFER_SIZE - 1, ZEROS_BUFFER_SIZE - 1,
          ZEROS_BUFFER_SIZE,
          ZEROS_BUFFER_SIZE + 1, ZEROS_BUFFER_SIZE + 2, ZEROS_BUFFER_SIZE + 3,
          ZEROS_BUFFER_SIZE * 2 - 2, ZEROS_BUFFER_SIZE * 2 - 1, ZEROS_BUFFER_SIZE * 2 - 1,
          ZEROS_BUFFER_SIZE * 2,
          ZEROS_BUFFER_SIZE * 2 + 1, ZEROS_BUFFER_SIZE * 2 + 2, ZEROS_BUFFER_SIZE * 2 + 3}) {
        res.add(arguments(offset, numZeros));
      }
    }
    return res;
  }

  @Nested
  class ZeroTests {
    @ParameterizedTest
    @MethodSource("org.postgresql.util.internal.PgBufferedOutputStreamTest#zerosOffsetAndNumber")
    void bufferFlushedOnlyWhenFull(int offset, int numZeros) throws IOException {
      AssertBufferedWrites dst = new AssertBufferedWrites(new ByteArrayOutputStream());
      int bufferSize = ZEROS_BUFFER_SIZE;
      PgBufferedOutputStream out = new PgBufferedOutputStream(dst, bufferSize);

      if (offset < bufferSize) {
        dst.forbidWrites("Writing less data than the buffer size should not cause flushes");
      } else {
        dst.allowWrites(bufferSize);
      }
      out.writeZeros(offset);
      if (numZeros + offset >= bufferSize) {
        dst.allowWrites(bufferSize);
      }
      out.writeZeros(numZeros);
      dst.allowWrites((numZeros + offset) % bufferSize);
      out.flush();
    }

    @ParameterizedTest
    @MethodSource("org.postgresql.util.internal.PgBufferedOutputStreamTest#zerosOffsetAndNumber")
    void writesZeros(int offset, int numZeros) throws IOException {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      int bufferSize = ZEROS_BUFFER_SIZE;
      PgBufferedOutputStream out = new PgBufferedOutputStream(baos, bufferSize);
      // Fill the buffer with non-zero values
      for (int i = 0; i < bufferSize; i++) {
        out.write(0xff);
      }
      // Shift the offset within the buffer
      for (int i = 0; i < offset; i++) {
        out.write(0xfe);
      }
      out.writeZeros(numZeros);
      out.write(0xca);
      out.flush();
      byte[] res = baos.toByteArray();
      assertAll(
          () -> assertEquals(bufferSize + offset + numZeros + 1, res.length,
              () -> "Result should have "
                  + bufferSize + " 0xff prefix bytes, "
                  + offset + " 0xfe offset bytes, "
                  + numZeros + " 0x00 zero bytes"
                  + ", and the final 0xca"
                  + ", result: " + Arrays.toString(res)),
          () -> {
            for (int i = 0; i < bufferSize; i++) {
              int pos = i;
              assertEquals(
                  0xff, res[pos] & 0xff,
                  () -> "bytes [0.." + bufferSize + ") should be 0xff as they were written"
                      + " with single-byte write(0xff) calls to fill the buffer"
                      + ", mismatch at position " + pos
                      + ", result: " + Arrays.toString(res));
            }
          },
          () -> {
            for (int i = 0; i < offset; i++) {
              int pos = bufferSize + i;
              assertEquals(
                  0xfe, res[pos] & 0xff,
                  () -> "bytes [" + bufferSize + ".." + (bufferSize + offset) + ")"
                      + " should be 0xfe as they were written"
                      + " with single-byte write(0xfe) calls to shift the buffer position"
                      + ", mismatch at position " + pos
                      + ", result: " + Arrays.toString(res));
            }
          },
          () -> {
            for (int i = 0; i < numZeros; i++) {
              int pos = bufferSize + offset + i;
              assertEquals(
                  0, res[pos] & 0xff,
                  () -> "bytes [" + (bufferSize + offset) + ".." + (bufferSize + offset + numZeros) + ")"
                      + " should be 0xff as they were written"
                      + " with writeZeros(len)"
                      + ", mismatch at position " + pos
                      + ", result: " + Arrays.toString(res));
            }
          },
          () -> {
            assertEquals(
                0xca, res[bufferSize + offset + numZeros] & 0xff,
                "the last byte should be 0xca as it was written with write(0xca)"
                    + " as a terminator char"
                    + ", result: " + Arrays.toString(res));
          }
      );
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
