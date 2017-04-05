/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.CharArrayReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.MalformedInputException;
import java.util.Arrays;

public class ReaderInputStreamTest {
  // 132878 = U+2070E - chosen because it is the first supplementary character
  // in the International Ideographic Core (IICore)
  // see http://www.i18nguy.com/unicode/supplementary-test.html for further explanation

  // Character.highSurrogate(132878) = 0xd841
  static final private char LEADING_SURROGATE = 0xd841;

  // Character.lowSurrogate(132878) = 0xdf0e
  static final private char TRAILING_SURROGATE = 0xdf0e;

  @Test(expected = IllegalArgumentException.class)
  public void NullReaderTest() {
    new ReaderInputStream(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void cbufTooSmallReaderTest() {
    new ReaderInputStream(new StringReader("abc"), 1);
  }

  static private void read(InputStream is, int... expected) throws IOException {
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
      assertEquals("should be end-of-stream", -1, nActual);
      is.close();
    }
  }

  @Test
  public void SimpleTest() throws IOException {
    char[] chars = new char[]{'a', 'b', 'c'};
    Reader reader = new CharArrayReader(chars);
    InputStream is = new ReaderInputStream(reader);
    read(is, 0x61, 0x62, 0x63);
    read(is);
  }

  @Test
  public void inputSmallerThanCbufsizeTest() throws IOException {
    char[] chars = new char[]{'a'};
    Reader reader = new CharArrayReader(chars);
    InputStream is = new ReaderInputStream(reader, 2);
    read(is, 0x61);
    read(is);
  }

  @Test
  public void tooManyReadsTest() throws IOException {
    char[] chars = new char[]{'a'};
    Reader reader = new CharArrayReader(chars);
    InputStream is = new ReaderInputStream(reader, 2);
    read(is, 0x61);
    assertEquals("should be end-of-stream", -1, is.read());
    assertEquals("should be end-of-stream", -1, is.read());
    assertEquals("should be end-of-stream", -1, is.read());
    is.close();
  }

  @Test
  public void surrogatePairSpansCharBufBoundaryTest() throws IOException {
    char[] chars = new char[]{'a', LEADING_SURROGATE, TRAILING_SURROGATE};
    Reader reader = new CharArrayReader(chars);
    InputStream is = new ReaderInputStream(reader, 2);
    read(is, 0x61, 0xF0, 0xA0, 0x9C);
    read(is, 0x8E);
    read(is);
  }

  @Test(expected = MalformedInputException.class)
  public void invalidInputTest() throws IOException {
    char[] chars = new char[]{'a', LEADING_SURROGATE, LEADING_SURROGATE};
    Reader reader = new CharArrayReader(chars);
    InputStream is = new ReaderInputStream(reader, 2);
    read(is);
  }

  @Test(expected = MalformedInputException.class)
  public void unmatchedLeadingSurrogateInputTest() throws IOException {
    char[] chars = new char[]{LEADING_SURROGATE};
    Reader reader = new CharArrayReader(chars);
    InputStream is = new ReaderInputStream(reader, 2);
    read(is, 0x00);
  }

  @Test(expected = MalformedInputException.class)
  public void unmatchedTrailingSurrogateInputTest() throws IOException {
    char[] chars = new char[]{TRAILING_SURROGATE};
    Reader reader = new CharArrayReader(chars);
    InputStream is = new ReaderInputStream(reader, 2);
    read(is);
  }

  @Test(expected = NullPointerException.class)
  public void nullArrayReadTest() throws IOException {
    Reader reader = new StringReader("abc");
    InputStream is = new ReaderInputStream(reader);
    is.read(null, 0, 4);
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void invalidOffsetArrayReadTest() throws IOException {
    Reader reader = new StringReader("abc");
    InputStream is = new ReaderInputStream(reader);
    byte[] bytes = new byte[4];
    is.read(bytes, 5, 4);
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void negativeOffsetArrayReadTest() throws IOException {
    Reader reader = new StringReader("abc");
    InputStream is = new ReaderInputStream(reader);
    byte[] bytes = new byte[4];
    is.read(bytes, -1, 4);
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void invalidLengthArrayReadTest() throws IOException {
    Reader reader = new StringReader("abc");
    InputStream is = new ReaderInputStream(reader);
    byte[] bytes = new byte[4];
    is.read(bytes, 1, 4);
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void negativeLengthArrayReadTest() throws IOException {
    Reader reader = new StringReader("abc");
    InputStream is = new ReaderInputStream(reader);
    byte[] bytes = new byte[4];
    is.read(bytes, 1, -2);
  }

  @Test
  public void zeroLengthArrayReadTest() throws IOException {
    Reader reader = new StringReader("abc");
    InputStream is = new ReaderInputStream(reader);
    byte[] bytes = new byte[4];
    assertEquals("requested 0 byte read", 0, is.read(bytes, 1, 0));
  }

  @Test
  public void singleCharArrayReadTest() throws IOException {
    Reader reader = new SingleCharPerReadReader(LEADING_SURROGATE, TRAILING_SURROGATE);
    InputStream is = new ReaderInputStream(reader);
    read(is, 0xF0, 0xA0, 0x9C, 0x8E);
    read(is);
  }

  @Test(expected = MalformedInputException.class)
  public void malformedSingleCharArrayReadTest() throws IOException {
    Reader reader = new SingleCharPerReadReader(LEADING_SURROGATE, LEADING_SURROGATE);
    InputStream is = new ReaderInputStream(reader);
    read(is, 0xF0, 0xA0, 0x9C, 0x8E);
  }

  @Test
  public void readsSmallerThanBlockSizeTest() throws Exception {
    final int BLOCK = 8 * 1024;
    final int DATASIZE = BLOCK * 5 + 57;
    final byte[] data = new byte[DATASIZE];
    final byte[] buffer = new byte[BLOCK];

    InputStreamReader isr = new InputStreamReader(new ByteArrayInputStream(data));
    ReaderInputStream r = new ReaderInputStream(isr);

    int read;
    int total = 0;

    while ((read = r.read(buffer, 0, BLOCK)) > -1) {
      total += read;
    }

    assertEquals("Data not read completely: missing " + (DATASIZE - total) + " bytes", total, DATASIZE);
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
