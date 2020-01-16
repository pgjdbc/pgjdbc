/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.util.GT;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

/**
 * UTF-8 encoder implementation which validates values during decoding which is
 * significantly faster than using a {@link CharsetDecoder}.
 */
abstract class OptimizedUTF8Encoder extends Encoding {

  static final Charset UTF_8_CHARSET = Charset.forName("UTF-8");

  private static final int MIN_2_BYTES = 0x80;
  private static final int MIN_3_BYTES = 0x800;
  private static final int MIN_4_BYTES = 0x10000;
  private static final int MAX_CODE_POINT = 0x10ffff;

  private final int thresholdSize = 8 * 1024;
  private char[] decoderArray;

  OptimizedUTF8Encoder() {
    super(UTF_8_CHARSET, true);
    decoderArray = new char[1024];
  }

  /**
   * Returns a {@code char[]} to use for decoding. Will use member variable if <i>size</i>
   * is small enough. This method must be called, and returned {@code char[]} only used, from
   * {@code synchronized} block.
   *
   * @param size
   *          The needed size of returned {@code char[]}.
   * @return
   *          A {@code char[]} at least as long as <i>length</i>.
   */
  char[] getCharArray(int size) {
    if (size <= decoderArray.length) {
      return decoderArray;
    }
    final char[] chars = new char[size];
    //only if size is below the threshold do we want to keep new char[] for future reuse
    if (size <= thresholdSize) {
      decoderArray = chars;
    }
    return chars;
  }

  /**
   * Decodes binary content to {@code String} by first converting to {@code char[]}.
   */
  synchronized String charDecode(byte[] encodedString, int offset, int length) throws IOException {
    final char[] chars = getCharArray(length);
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

  /**
   * Decodes <i>data</i> from <i>offset</i> with given <i>length</i> as utf-8 and
   * gives each decoded code point to the <i>codePointConsumer</i>.
   *
   * @param data
   *          The {@code byte[]} to decode.
   * @param offset
   *          The starting index in <i>data</i>.
   * @param length
   *          The number of bytes in <i>data</i> to decode.
   * @param codePointConsumer
   *          The consumer of all decoded code points.
   * @throws IOException
   *          If data is not valid utf-8 content.
   */
  static String decodeToChars(byte[] data, int offset, int length, char[] chars, int out) throws IOException {
    int in = offset;
    final int end = length + offset;

    try {
      while (in < end) {
        int ch = data[in++] & 0xff;

        // Convert UTF-8 to 21-bit codepoint.
        if (ch < 0x80) {
          // 0xxxxxxx -- length 1.
        } else if (ch < 0xc0) {
          // 10xxxxxx -- illegal!
          throw new IOException(GT.tr("Illegal UTF-8 sequence: initial byte is {0}: {1}",
              "10xxxxxx", ch));
        } else if (ch < 0xe0) {
          // 110xxxxx 10xxxxxx
          ch = ((ch & 0x1f) << 6);
          checkByte(data[in], 2, 2);
          ch = ch | (data[in++] & 0x3f);
          checkMinimal(ch, MIN_2_BYTES);
        } else if (ch < 0xf0) {
          // 1110xxxx 10xxxxxx 10xxxxxx
          ch = ((ch & 0x0f) << 12);
          checkByte(data[in], 2, 3);
          ch = ch | ((data[in++] & 0x3f) << 6);
          checkByte(data[in], 3, 3);
          ch = ch | (data[in++] & 0x3f);
          checkMinimal(ch, MIN_3_BYTES);
        } else if (ch < 0xf8) {
          // 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
          ch = ((ch & 0x07) << 18);
          checkByte(data[in], 2, 4);
          ch = ch | ((data[in++] & 0x3f) << 12);
          checkByte(data[in], 3, 4);
          ch = ch | ((data[in++] & 0x3f) << 6);
          checkByte(data[in], 4, 4);
          ch = ch | (data[in++] & 0x3f);
          checkMinimal(ch, MIN_4_BYTES);
        } else {
          throw new IOException(GT.tr("Illegal UTF-8 sequence: initial byte is {0}: {1}",
              "11111xxx", ch));
        }

        if (ch > MAX_CODE_POINT) {
          throw new IOException(
              GT.tr("Illegal UTF-8 sequence: final value is out of range: {0}", ch));
        }
        // Convert 21-bit codepoint to Java chars:
        // 0..ffff are represented directly as a single char
        // 10000..10ffff are represented as a "surrogate pair" of two chars
        // See: http://java.sun.com/developer/technicalArticles/Intl/Supplementary/
        if (ch > 0xffff) {
          // Use a surrogate pair to represent it.
          ch -= 0x10000; // ch is now 0..fffff (20 bits)
          chars[out++] = (char) (0xd800 + (ch >> 10)); // top 10 bits
          chars[out++] = (char) (0xdc00 + (ch & 0x3ff)); // bottom 10 bits
        } else if (ch >= 0xd800 && ch < 0xe000) {
          // Not allowed to encode the surrogate range directly.
          throw new IOException(GT.tr("Illegal UTF-8 sequence: final value is a surrogate value: {0}", ch));
        } else {
          // Normal case.
          chars[out++] = (char) ch;
        }
      }
    } catch (ArrayIndexOutOfBoundsException a) {
      throw new IOException("Illegal UTF-8 sequence: multibyte sequence was truncated");
    }
    return new String(chars, 0, out);
  }

  // helper for decode
  private static void checkByte(int ch, int pos, int len) throws IOException {
    if ((ch & 0xc0) != 0x80) {
      throw new IOException(
          GT.tr("Illegal UTF-8 sequence: byte {0} of {1} byte sequence is not 10xxxxxx: {2}", pos, len, ch));
    }
  }

  private static void checkMinimal(int ch, int minValue) throws IOException {
    if (ch >= minValue) {
      return;
    }

    int actualLen;
    switch (minValue) {
      case MIN_2_BYTES:
        actualLen = 2;
        break;
      case MIN_3_BYTES:
        actualLen = 3;
        break;
      case MIN_4_BYTES:
        actualLen = 4;
        break;
      default:
        throw new IllegalArgumentException("unexpected minValue passed to checkMinimal: " + minValue);
    }

    int expectedLen;
    if (ch < MIN_2_BYTES) {
      expectedLen = 1;
    } else if (ch < MIN_3_BYTES) {
      expectedLen = 2;
    } else if (ch < MIN_4_BYTES) {
      expectedLen = 3;
    } else {
      throw new IllegalArgumentException("unexpected ch passed to checkMinimal: " + ch);
    }

    throw new IOException(
        GT.tr("Illegal UTF-8 sequence: {0} bytes used to encode a {1} byte value: {2}", actualLen, expectedLen, ch));
  }
}
