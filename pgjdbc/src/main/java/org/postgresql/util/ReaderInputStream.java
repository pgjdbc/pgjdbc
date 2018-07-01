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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;

/**
 * <p>ReaderInputStream accepts a UTF-16 char stream (Reader) as input and
 * converts it to a UTF-8 byte stream (InputStream) as output.</p>
 *
 * <p>This is the inverse of java.io.InputStreamReader which converts a
 * binary stream to a character stream.</p>
 */
public class ReaderInputStream extends InputStream {
  private static final int DEFAULT_CHAR_BUFFER_SIZE = 8 * 1024;

  private static final Charset UTF_8 = Charset.forName("UTF-8");

  private final Reader reader;
  private final CharsetEncoder encoder;
  private final ByteBuffer bbuf;
  private final CharBuffer cbuf;

  /**
   * true when all of the characters have been read from the reader into inbuf.
   */
  private boolean endOfInput;
  private final byte[] oneByte = new byte[1];

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
    this.bbuf.flip(); // prepare for subsequent write
    this.cbuf = CharBuffer.allocate(charBufferSize);
    this.cbuf.flip(); // prepare for subsequent write
  }

  private void advance() throws IOException {
    assert !endOfInput;
    assert !bbuf.hasRemaining()
        : "advance() should be called when output byte buffer is empty. bbuf: " + bbuf + ", as string: " + bbuf.asCharBuffer().toString();
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

    CoderResult result;

    endOfInput = n == -1;

    bbuf.clear();
    result = encoder.encode(cbuf, bbuf, endOfInput);
    checkEncodeResult(result);

    if (endOfInput) {
      result = encoder.flush(bbuf);
      checkEncodeResult(result);
    }

    bbuf.flip();
  }

  private void checkEncodeResult(CoderResult result) throws CharacterCodingException {
    if (result.isError()) {
      result.throwException();
    }
  }

  @Override
  public int read() throws IOException {
    int res = 0;
    while (res != -1) {
      res = read(oneByte);
      if (res > 0) {
        return oneByte[0];
      }
    }
    return -1;
  }

  // The implementation of InputStream.read(byte[], int, int) silently ignores
  // an IOException thrown by overrides of the read() method.
  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    if (b == null) {
      throw new NullPointerException();
    } else if (off < 0 || len < 0 || len > b.length - off) {
      throw new IndexOutOfBoundsException();
    } else if (len == 0) {
      return 0;
    }
    if (endOfInput && !bbuf.hasRemaining()) {
      return -1;
    }

    int totalRead = 0;
    while (len > 0 && !endOfInput) {
      if (bbuf.hasRemaining()) {
        int remaining = Math.min(len, bbuf.remaining());
        bbuf.get(b, off, remaining);
        totalRead += remaining;
        off += remaining;
        len -= remaining;
        if (len == 0) {
          return totalRead;
        }
      }
      advance();
    }
    if (endOfInput && !bbuf.hasRemaining() && totalRead == 0) {
      return -1;
    }
    return totalRead;
  }

  @Override
  public void close() throws IOException {
    endOfInput = true;
    reader.close();
  }
}
