/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.MalformedInputException;

/**
 * ReaderInputStream accepts a UTF-16 char stream (Reader) as input and
 * converts it to a UTF-8 byte stream (InputStream) as output.
 *
 * This is the inverse of java.io.InputStreamReader which converts a
 * binary stream to a character stream.
 */
public class ReaderInputStream extends InputStream {
  private static final int DEFAULT_CHAR_BUFFER_SIZE = 8 * 1024;

  private static final Charset UTF_8 = Charset.availableCharsets().get("UTF-8");

  private final Reader reader;
  private final CharsetEncoder encoder;
  private final ByteBuffer bbuf;
  private final CharBuffer cbuf;

  /** true when all of the characters have been read from the reader into inbuf */
  private boolean endOfInput;

  public ReaderInputStream(Reader reader) {
    this(reader, DEFAULT_CHAR_BUFFER_SIZE);
  }

  /**
   * Allow ReaderInputStreamTest to use small buffers to force UTF-16
   * surrogate pairs to cross buffer boundaries in interesting ways.
   * Because this constructor is package-private, the unit test must be in
   * the same package.
   */
  ReaderInputStream(Reader reader, int charBufferSize) {
    if (reader == null) {
      throw new IllegalArgumentException("reader cannot be null");
    }

    // The standard UTF-8 encoder will only encode a UTF-16 surrogate pair
    // when both surrogates are available in the CharBuffer.
    if (charBufferSize < 2) {
      throw new IllegalArgumentException("charBufferSize must be at least 2 chars");
    }

    this.reader = reader;
    this.encoder = UTF_8.newEncoder();
    // encoder.maxBytesPerChar() always returns 3.0 for UTF-8
    this.bbuf = ByteBuffer.allocate(3 * charBufferSize);
    this.bbuf.compact();
    this.cbuf = CharBuffer.allocate(charBufferSize);
    this.cbuf.compact();
  }

  private void encode() throws IOException {
    assert !endOfInput;
    assert !bbuf.hasRemaining();
    assert cbuf.remaining() < 2;

    // given that bbuf.capacity = 3 x cbuf.capacity, the only time that we should have a
    // remaining char is if the last char read was the 1st half of a surrogate pair
    if (cbuf.remaining() == 0) {
      cbuf.clear();
    } else {
      cbuf.compact();
    }

    int n = reader.read(cbuf);  // read #1
    cbuf.flip();

    bbuf.clear();

    CoderResult result;

    if (n == -1) {
      endOfInput = true;
      result = encoder.encode(cbuf, bbuf, endOfInput);

      if (result.isError()) {
        result.throwException();
      }

      result = encoder.flush(bbuf);
    } else {
      result = encoder.encode(cbuf, bbuf, endOfInput);

      if (bbuf.position() == 0) {
        if (result.isError()) {
          result.throwException();
        }

        cbuf.compact();
        n = reader.read(cbuf);  // read #2
        cbuf.flip();

        if (n == -1) {
          endOfInput = true;
          result = encoder.encode(cbuf, bbuf, endOfInput);

          if (result.isError()) {
            result.throwException();
          }

          result = encoder.flush(bbuf);
        } else {
          // this point can only be reached when read #1 returns 1 char which is an
          // unmatched surrogate and then read #2 returns at least 1 more char
          result = encoder.encode(cbuf, bbuf, endOfInput);

          if (result.isError()) {
            result.throwException();
          }

          // a valid input stream should not be able to reach
          // this point without some bytes being encoded
          if (bbuf.position() == 0) {
            throw new MalformedInputException(cbuf.remaining());
          }
        }
      }
    }

    if (result.isError()) {
      result.throwException();
    }

    bbuf.flip();
  }

  @Override
  public int read() throws IOException {
    int result = -1;
    if (bbuf.hasRemaining()) {
      result = bbuf.get();
    } else if (!endOfInput) {
      encode();
      if (bbuf.hasRemaining()) {
        result = bbuf.get();
      }
    } else {
      result = -1;
    }

    return result;
  }

  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  // The implementation of InputStream.read(byte[], int, int) silently ignores
  // an IOException thrown by overrides of the read() method.
  @Override
  public int read(byte b[], int off, int len) throws IOException {
    if (b == null) {
      throw new NullPointerException();
    } else if (off < 0 || len < 0 || len > b.length - off) {
      throw new IndexOutOfBoundsException();
    } else if (len == 0) {
      return 0;
    }

    int i = 0;
    while (i < len) {
      int x = read();
      if (x == -1) {
        break;
      }
      b[off + i] = (byte)(x & 0xFF);
      i++;
    }

    if (i == 0) {
      i = -1;
    }
    return i;
  }

  @Override
  public void close() throws IOException {
    endOfInput = true;
    reader.close();
  }
}
