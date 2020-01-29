/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.IOException;
import java.nio.charset.Charset;

/**
 * UTF-8 encoder which validates input and is optimized for jdk 9+ where {@code String} objects are backed by
 * {@code byte[]}.
 * @author Brett Okken
 */
final class ByteOptimizedUTF8Encoder extends OptimizedUTF8Encoder {

  private static final Charset ASCII_CHARSET = Charset.forName("ascii");

  /**
   * {@inheritDoc}
   */
  @Override
  public String decode(byte[] encodedString, int offset, int length) throws IOException {
    //for very short strings going straight to chars is up to 30% faster
    if (length <= 32) {
      return charDecode(encodedString, offset, length);
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

  /**
   * Decodes to {@code char[]} in presence of non-ascii values after first copying all known ascii chars directly
   * from {@code byte[]} to {@code char[]}.
   */
  private synchronized String slowDecode(byte[] encodedString, int offset, int length, int curIdx) throws IOException {
    final char[] chars = getCharArray(length);
    int out = 0;
    for (int i = offset; i < curIdx; ++i) {
      chars[out++] = (char) encodedString[i];
    }
    return decodeToChars(encodedString, curIdx, length - (curIdx - offset), chars, out);
  }
}
