/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.CharArrayReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.MalformedInputException;
import java.util.Arrays;

class ReaderInputStreamTest {
  // 132878 = U+2070E - chosen because it is the first supplementary character
  // in the International Ideographic Core (IICore)
  // see http://www.i18nguy.com/unicode/supplementary-test.html for further explanation

  // Character.highSurrogate(132878) = 0xd841
  private static final char LEADING_SURROGATE = 0xd841;

  // Character.lowSurrogate(132878) = 0xdf0e
  private static final char TRAILING_SURROGATE = 0xdf0e;

  @Test
  @SuppressWarnings("nullability")
  void NullReaderTest() {
    assertThrows(IllegalArgumentException.class, () -> {
      new ReaderInputStream(null);
    });
  }

  @Test
  void cbufTooSmallReaderTest() {
    assertThrows(IllegalArgumentException.class, () -> {
      new ReaderInputStream(new StringReader("abc"), 1);
    });
  }

  private static void read(InputStream is, int... expected) throws IOException {
    byte[] actual = new byte[4];
    Arrays.fill(actual, (byte) 0x00);
    int nActual = is.read(actual);
    int[] actualInts = new int[4];
    for (int i = 0; i < actual.length; i++) {
      actualInts[i] = actual[i] & 0xff;
    }
    if (expected.length > 0) {
      // Ensure "expected" has 4 bytes
      expected = Arrays.copyOf(expected, 4);
      assertEquals(Arrays.toString(expected), Arrays.toString(actualInts));
    } else {
      assertEquals(-1, nActual, "should be end-of-stream");
      is.close();
    }
  }

  @Test
  void SimpleTest() throws IOException {
    char[] chars = {'a', 'b', 'c'};
    Reader reader = new CharArrayReader(chars);
    InputStream is = new ReaderInputStream(reader);
    read(is, 0x61, 0x62, 0x63);
    read(is);
  }

  @Test
  void inputSmallerThanCbufsizeTest() throws IOException {
    char[] chars = {'a'};
    Reader reader = new CharArrayReader(chars);
    InputStream is = new ReaderInputStream(reader, 2);
    read(is, 0x61);
    read(is);
  }

  @Test
  void tooManyReadsTest() throws IOException {
    char[] chars = {'a'};
    Reader reader = new CharArrayReader(chars);
    InputStream is = new ReaderInputStream(reader, 2);
    read(is, 0x61);
    assertEquals(-1, is.read(), "should be end-of-stream");
    assertEquals(-1, is.read(), "should be end-of-stream");
    assertEquals(-1, is.read(), "should be end-of-stream");
    is.close();
  }

  @Test
  void surrogatePairSpansCharBufBoundaryTest() throws IOException {
    char[] chars = {'a', LEADING_SURROGATE, TRAILING_SURROGATE};
    Reader reader = new CharArrayReader(chars);
    InputStream is = new ReaderInputStream(reader, 2);
    read(is, 0x61, 0xF0, 0xA0, 0x9C);
    read(is, 0x8E);
    read(is);
  }

  @Test
  void invalidInputTest() throws IOException {
    assertThrows(MalformedInputException.class, () -> {
      char[] chars = {'a', LEADING_SURROGATE, LEADING_SURROGATE};
      Reader reader = new CharArrayReader(chars);
      InputStream is = new ReaderInputStream(reader, 2);
      read(is);
    });
  }

  @Test
  void unmatchedLeadingSurrogateInputTest() throws IOException {
    assertThrows(MalformedInputException.class, () -> {
      char[] chars = {LEADING_SURROGATE};
      Reader reader = new CharArrayReader(chars);
      InputStream is = new ReaderInputStream(reader, 2);
      read(is, 0x00);
    });
  }

  @Test
  void unmatchedTrailingSurrogateInputTest() throws IOException {
    assertThrows(MalformedInputException.class, () -> {
      char[] chars = {TRAILING_SURROGATE};
      Reader reader = new CharArrayReader(chars);
      InputStream is = new ReaderInputStream(reader, 2);
      read(is);
    });
  }

  @Test
  @SuppressWarnings("nullness")
  void nullArrayReadTest() throws IOException {
    assertThrows(NullPointerException.class, () -> {
      Reader reader = new StringReader("abc");
      InputStream is = new ReaderInputStream(reader);
      is.read(null, 0, 4);
    });
  }

  @Test
  void invalidOffsetArrayReadTest() throws IOException {
    assertThrows(IndexOutOfBoundsException.class, () -> {
      Reader reader = new StringReader("abc");
      InputStream is = new ReaderInputStream(reader);
      byte[] bytes = new byte[4];
      is.read(bytes, 5, 4);
    });
  }

  @Test
  void negativeOffsetArrayReadTest() throws IOException {
    assertThrows(IndexOutOfBoundsException.class, () -> {
      Reader reader = new StringReader("abc");
      InputStream is = new ReaderInputStream(reader);
      byte[] bytes = new byte[4];
      is.read(bytes, -1, 4);
    });
  }

  @Test
  void invalidLengthArrayReadTest() throws IOException {
    assertThrows(IndexOutOfBoundsException.class, () -> {
      Reader reader = new StringReader("abc");
      InputStream is = new ReaderInputStream(reader);
      byte[] bytes = new byte[4];
      is.read(bytes, 1, 4);
    });
  }

  @Test
  void negativeLengthArrayReadTest() throws IOException {
    assertThrows(IndexOutOfBoundsException.class, () -> {
      Reader reader = new StringReader("abc");
      InputStream is = new ReaderInputStream(reader);
      byte[] bytes = new byte[4];
      is.read(bytes, 1, -2);
    });
  }

  @Test
  void zeroLengthArrayReadTest() throws IOException {
    Reader reader = new StringReader("abc");
    InputStream is = new ReaderInputStream(reader);
    byte[] bytes = new byte[4];
    assertEquals(0, is.read(bytes, 1, 0), "requested 0 byte read");
  }

  @Test
  void singleCharArrayReadTest() throws IOException {
    Reader reader = new SingleCharPerReadReader(LEADING_SURROGATE, TRAILING_SURROGATE);
    InputStream is = new ReaderInputStream(reader);
    read(is, 0xF0, 0xA0, 0x9C, 0x8E);
    read(is);
  }

  @Test
  void malformedSingleCharArrayReadTest() throws IOException {
    assertThrows(MalformedInputException.class, () -> {
      Reader reader = new SingleCharPerReadReader(LEADING_SURROGATE, LEADING_SURROGATE);
      InputStream is = new ReaderInputStream(reader);
      read(is, 0xF0, 0xA0, 0x9C, 0x8E);
    });
  }

  @Test
  void readsEqualToBlockSizeTest() throws Exception {
    final int blockSize = 8 * 1024;
    final int dataSize = blockSize + 57;
    final byte[] data = new byte[dataSize];
    final byte[] buffer = new byte[blockSize];

    InputStreamReader isr = new InputStreamReader(new ByteArrayInputStream(data), "UTF-8");
    ReaderInputStream r = new ReaderInputStream(isr, blockSize);

    int total = 0;

    total += r.read(buffer, 0, blockSize);
    total += r.read(buffer, 0, blockSize);

    assertEquals(dataSize, total, "Data not read completely: missing " + (dataSize - total) + " bytes");
  }

  private static class SingleCharPerReadReader extends Reader {
    private final char[] data;
    private int i;

    private SingleCharPerReadReader(char... data) {
      this.data = data;
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
      if (i < data.length) {
        cbuf[off] = data[i++];
        return 1;
      }

      return -1;
    }

    @Override
    public void close() throws IOException {
    }
  }
}
