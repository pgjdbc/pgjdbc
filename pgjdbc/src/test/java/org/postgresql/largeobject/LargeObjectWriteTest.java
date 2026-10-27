/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.fastpath.LowriteRecorder;
import org.postgresql.util.ByteStreamWriter;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

/**
 * {@link LargeObject#write} sends at most the lowrite limit per {@code lowrite} call, and the calls
 * together write exactly the bytes the caller passed. The tests set the limit to 4 bytes through
 * the package-private constructor, except the two writer tests of about 1 GiB, which use the real
 * one.
 */
class LargeObjectWriteTest {
  private final LowriteRecorder recorder = new LowriteRecorder(true);

  private LargeObject largeObjectWithLowriteLimit(int maxLowriteLength) throws SQLException {
    return new LargeObject(recorder, 0, LargeObjectManager.READWRITE, null, false,
        maxLowriteLength);
  }

  private static byte[] bytes(int... values) {
    byte[] result = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      result[i] = (byte) values[i];
    }
    return result;
  }

  @Test
  void anArrayOfTheLimitGoesInOneLowrite() throws SQLException {
    largeObjectWithLowriteLimit(4).write(bytes(1, 2, 3, 4), 0, 4);
    assertEquals(Collections.singletonList(4), recorder.lowriteLengths());
  }

  @Test
  void anArrayOneByteOverTheLimitGoesInTwoLowrites() throws SQLException {
    largeObjectWithLowriteLimit(4).write(bytes(1, 2, 3, 4, 5), 0, 5);
    assertAll(
        () -> assertEquals(Arrays.asList(4, 1), recorder.lowriteLengths(), "lowrite lengths"),
        () -> assertArrayEquals(bytes(1, 2, 3, 4, 5), recorder.contents(), "contents"));
  }

  @Test
  void anArrayOfTwiceTheLimitSendsNoEmptyLowrite() throws SQLException {
    largeObjectWithLowriteLimit(4).write(bytes(1, 2, 3, 4, 5, 6, 7, 8), 0, 8);
    assertEquals(Arrays.asList(4, 4), recorder.lowriteLengths());
  }

  @Test
  void anArrayOverTheLimitIsSentFromTheGivenOffset() throws SQLException {
    largeObjectWithLowriteLimit(4).write(bytes(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), 2, 9);
    assertAll(
        () -> assertEquals(Arrays.asList(4, 4, 1), recorder.lowriteLengths(), "lowrite lengths"),
        () -> assertArrayEquals(bytes(2, 3, 4, 5, 6, 7, 8, 9, 10), recorder.contents(),
            "contents"));
  }

  @Test
  void aWholeArrayOverTheLimitGoesInSeveralLowrites() throws SQLException {
    largeObjectWithLowriteLimit(4).write(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9));
    assertAll(
        () -> assertEquals(Arrays.asList(4, 4, 1), recorder.lowriteLengths(), "lowrite lengths"),
        () -> assertArrayEquals(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9), recorder.contents(),
            "contents"));
  }

  @Test
  void aFailedLowriteStopsAnArrayWrite() throws SQLException {
    SQLException failure = new SQLException("lowrite failed");
    recorder.failLowrite(2, failure);
    LargeObject lo = largeObjectWithLowriteLimit(4);
    SQLException thrown =
        assertThrows(SQLException.class, () -> lo.write(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9), 0, 9));
    assertAll(
        () -> assertSame(failure, thrown, "exception"),
        () -> assertEquals(Collections.singletonList(4), recorder.lowriteLengths(),
            "lowrite lengths"));
  }

  /**
   * Writes the body's output into the target stream; reports {@code length} as its length.
   */
  private static ByteStreamWriter writer(int length, WriteBody body) {
    return new ByteStreamWriter() {
      @Override
      public int getLength() {
        return length;
      }

      @Override
      public void writeTo(ByteStreamTarget target) throws IOException {
        body.writeTo(target.getOutputStream());
      }
    };
  }

  private interface WriteBody {
    void writeTo(OutputStream out) throws IOException;
  }

  @Test
  void aWriterOfTheLimitStreamsInOneLowrite() throws SQLException {
    largeObjectWithLowriteLimit(4).write(writer(4, out -> out.write(bytes(1, 2, 3, 4))));
    assertAll(
        () -> assertEquals(Collections.singletonList(4), recorder.lowriteLengths(),
            "lowrite lengths"),
        () -> assertArrayEquals(bytes(1, 2, 3, 4), recorder.contents(), "contents"));
  }

  @Test
  void aWriterOverTheLimitGoesInLowritesOfTheLimit() throws SQLException {
    largeObjectWithLowriteLimit(4).write(
        writer(10, out -> out.write(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))));
    assertAll(
        () -> assertEquals(Arrays.asList(4, 4, 2), recorder.lowriteLengths(), "lowrite lengths"),
        () -> assertArrayEquals(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), recorder.contents(),
            "contents"));
  }

  @Test
  void aWriterOverTheLimitThatWritesSingleBytesGoesInLowritesOfTheLimit() throws SQLException {
    largeObjectWithLowriteLimit(4).write(writer(10, out -> {
      for (int b = 1; b <= 10; b++) {
        out.write(b);
      }
    }));
    assertAll(
        () -> assertEquals(Arrays.asList(4, 4, 2), recorder.lowriteLengths(), "lowrite lengths"),
        () -> assertArrayEquals(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), recorder.contents(),
            "contents"));
  }

  @Test
  void aWriterOverTheLimitThatWritesFewerBytesIsPaddedWithZeros() throws SQLException {
    largeObjectWithLowriteLimit(4).write(writer(10, out -> out.write(bytes(1, 2, 3, 4, 5))));
    assertAll(
        () -> assertEquals(Arrays.asList(4, 4, 2), recorder.lowriteLengths(), "lowrite lengths"),
        () -> assertArrayEquals(bytes(1, 2, 3, 4, 5, 0, 0, 0, 0, 0), recorder.contents(),
            "contents"));
  }

  @Test
  void aWriterOverTheLimitThatWritesMoreBytesThanItsLengthFails() throws SQLException {
    LargeObject lo = largeObjectWithLowriteLimit(4);
    SQLException thrown = assertThrows(SQLException.class,
        () -> lo.write(writer(6, out -> out.write(bytes(1, 2, 3, 4, 5, 6, 7)))));
    assertAll(
        () -> assertInstanceOf(IOException.class, thrown.getCause(), "cause"),
        () -> assertEquals(Collections.emptyList(), recorder.lowriteLengths(),
            "lowrite lengths"));
  }

  @Test
  void anIOExceptionFromAWriterOverTheLimitBecomesTheCause() throws SQLException {
    IOException failure = new IOException("source failed");
    LargeObject lo = largeObjectWithLowriteLimit(4);
    SQLException thrown = assertThrows(SQLException.class,
        () -> lo.write(writer(10, out -> {
          out.write(bytes(1, 2, 3, 4, 5));
          throw failure;
        })));
    assertAll(
        () -> assertSame(failure, thrown.getCause(), "cause"),
        () -> assertEquals(PSQLState.DATA_ERROR.getState(), thrown.getSQLState(), "SQLState"),
        () -> assertEquals(Collections.singletonList(4), recorder.lowriteLengths(),
            "lowrite lengths"));
  }

  /**
   * The writer catches the {@code IOException} its target stream throws and keeps writing, so the
   * failure reaches the caller only through the stream's own record of it.
   */
  @Test
  void aFailedLowriteIsThrownEvenWhenTheWriterCatchesIt() throws SQLException {
    SQLException failure = new SQLException("lowrite failed");
    recorder.failLowrite(2, failure);
    LargeObject lo = largeObjectWithLowriteLimit(4);
    SQLException thrown = assertThrows(SQLException.class,
        () -> lo.write(writer(10, out -> {
          for (int b = 1; b <= 10; b++) {
            try {
              out.write(b);
            } catch (IOException ignored) {
              // keep writing
            }
          }
        })));
    assertAll(
        () -> assertSame(failure, thrown, "exception"),
        () -> assertEquals(Collections.singletonList(4), recorder.lowriteLengths(),
            "lowrite lengths"));
  }

  /**
   * Writes {@code length} zero bytes in 64 KiB writes, so a test can send about 1 GiB without an
   * array that large.
   */
  private static ByteStreamWriter zeros(int length) {
    return writer(length, out -> {
      byte[] chunk = new byte[64 * 1024];
      for (int left = length; left > 0; left -= chunk.length) {
        out.write(chunk, 0, Math.min(left, chunk.length));
      }
    });
  }

  @Test
  void aWriterOfMaxLowriteLengthStreamsInOneLowrite() throws SQLException {
    LowriteRecorder lengthsOnly = new LowriteRecorder(false);
    new LargeObject(lengthsOnly, 0, LargeObjectManager.READWRITE).write(zeros(1073676288));
    assertEquals(Collections.singletonList(1073676288), lengthsOnly.lowriteLengths());
  }

  @Test
  void aWriterOneByteOverMaxLowriteLengthGoesInLowritesOf8MiB() throws SQLException {
    LowriteRecorder lengthsOnly = new LowriteRecorder(false);
    new LargeObject(lengthsOnly, 0, LargeObjectManager.READWRITE).write(zeros(1073676289));
    assertAll(
        () -> assertEquals(128, lengthsOnly.lowriteLengths().size(), "number of lowrites"),
        () -> assertEquals(Collections.singleton(8388608),
            new HashSet<>(lengthsOnly.lowriteLengths().subList(0, 127)),
            "lengths of all lowrites but the last"),
        () -> assertEquals(8323073, lengthsOnly.lowriteLengths().get(127), "last lowrite length"));
  }
}
