/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.IOException;

/**
 * An {@link Encoding} for utf-8 which is optimized for {@code String}
 * implementations before java 9 which are backed by {@code char[]}.
 *
 * @author Brett Okken
 */
final class CharOptimizedUTF8Encoding extends OptimizedUTF8Encoder {

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized String decode(byte[] encodedString, int offset, int length) throws IOException {
    final char[] chars = getChars(length);
    int out = 0;
    for (int i = offset, j = offset + length; i < j; ++i) {
      // bytes are signed values. all ascii values are positive
      if (encodedString[i] >= 0) {
        chars[out++] = (char) encodedString[i];
      } else {
        return decodeToChars(encodedString, i, j - i, chars, out);
      }
    }
    return new String(chars, 0, out);
  }
}
