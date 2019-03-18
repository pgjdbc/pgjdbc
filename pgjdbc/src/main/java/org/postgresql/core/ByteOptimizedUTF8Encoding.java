/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.IOException;
import java.nio.charset.Charset;

/**
 * An {@link Encoding} for utf-8 which is optimized for {@code String}
 * implementation starting with java 9 which is backed by {@code byte[]}.
 *
 * @author Brett Okken
 */
final class ByteOptimizedUTF8Encoding extends OptimizedUTF8Encoder {

  private static final Charset ASCII_CHARSET = Charset.forName("ascii");
  private static final Charset UTF_8_CHARSET = Charset.forName("UTF-8");

  /**
   * {@inheritDoc}
   */
  @Override
  public String decode(byte[] encodedString, int offset, int length) throws IOException {

    if (length >= MAX_CHAR_SIZE) {
      return new String(encodedString, offset, length, UTF_8_CHARSET);
    }

    for (int i = offset, j = offset + length; i < j; ++i) {
      // bytes are signed values. all ascii values are positive
      if (encodedString[i] < 0) {
        return slowDecode(encodedString, offset, length, i);
      }
    }
    // we have confirmed all chars are ascii, give java that hint
    return new String(encodedString, offset, length, ASCII_CHARSET);
  }

  private synchronized String slowDecode(byte[] encodedString, int offset, int length, int curIdx) throws IOException {
    final char[] chars = getChars(length);
    int out = 0;
    for (int i = offset; i < curIdx; ++i) {
      chars[out++] = (char) encodedString[i];
    }
    return decodeToChars(encodedString, curIdx, length - (curIdx - offset), chars, out);
  }
}
