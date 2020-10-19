/*
 * Copyright (c) 2011, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.math.BigDecimal;
import java.nio.CharBuffer;

/**
 * Helper methods to parse java base types from byte arrays.
 *
 * @author Mikko Tiihonen
 */
public class ByteConverter {

  private static final int NBASE = 10000;
  private static final int NUMERIC_DSCALE_MASK = 0x00003FFF;
  private static final short NUMERIC_POS = 0x0000;
  private static final short NUMERIC_NEG = 0x4000;
  private static final short NUMERIC_NAN = (short) 0xC000;
  private static final int DEC_DIGITS = 4;
  private static final int[] round_powers = {0, 1000, 100, 10};
  private static final int SHORT_BYTES = 2;
  private static final int LONG_BYTES = 4;

  private ByteConverter() {
    // prevent instantiation of static helper class
  }

  /**
   * Convert a variable length array of bytes to an integer
   * @param bytes array of bytes that can be decoded as an integer
   * @return integer
   */
  public static int bytesToInt(byte []bytes) {
    if ( bytes.length == 1 ) {
      return (int)bytes[0];
    }
    if ( bytes.length == SHORT_BYTES ) {
      return int2(bytes, 0);
    }
    if ( bytes.length == LONG_BYTES ) {
      return int4(bytes, 0);
    } else {
      throw new IllegalArgumentException("Argument bytes is empty");
    }
  }

  /**
   * Convert a number from binary representation to text representation.
   * @param idx index of the digit to be converted in the digits array
   * @param digits array of shorts that can be decoded as the number String
   * @param buffer the character buffer to put the text representation in
   * @param alwaysPutIt a flag that indicate whether or not to put the digit char even if it is zero
   * @return String the number as String
   */
  private static void digitToString(int idx, short[] digits, CharBuffer buffer, boolean alwaysPutIt) {
    short dig = (idx >= 0 && idx < digits.length) ? digits[idx] : 0;
    // Each dig represents 4 decimal digits (e.g. 9999)
    // If we continue the number, then we need to print 0 as 0000 (alwaysPutIt parameter is true)
    for (int p = 1; p < round_powers.length; p++) {
      int pow = round_powers[p];
      short d1 = (short)(dig / pow);
      dig -= d1 * pow;
      boolean putit = (d1 > 0);
      if (putit || alwaysPutIt) {
        buffer.put((char)(d1 + '0'));
        // We printed a character, so we need to print the rest of the current digits in dig
        // For instance, we need to keep printing 000 from 1000 even if idx==0 (== it is the very
        // beginning)
        alwaysPutIt = true;
      }
    }

    buffer.put((char)(dig + '0'));
  }

  /**
   * Convert a number from binary representation to text representation.
   * @param digits array of shorts that can be decoded as the number String
   * @param scale the scale of the number binary representation
   * @param weight the weight of the number binary representation
   * @param sign the sign of the number
   * @return String the number as String
   */
  private static String numberBytesToString(short[] digits, int scale, int weight, int sign) {
    CharBuffer buffer;
    int i;
    int d;

    /*
     * Allocate space for the result.
     *
     * i is set to the # of decimal digits before decimal point. dscale is the
     * # of decimal digits we will print after decimal point. We may generate
     * as many as DEC_DIGITS-1 excess digits at the end, and in addition we
     * need room for sign, decimal point, null terminator.
     */
    i = (weight + 1) * DEC_DIGITS;
    if (i <= 0) {
      i = 1;
    }

    buffer = CharBuffer.allocate((i + scale + DEC_DIGITS + 2));

    /*
     * Output a dash for negative values
     */
    if (sign == NUMERIC_NEG) {
      buffer.put('-');
    }

    /*
     * Output all digits before the decimal point
     */
    if (weight < 0) {
      d = weight + 1;
      buffer.put('0');
    } else {
      for (d = 0; d <= weight; d++) {
        /* In the first digit, suppress extra leading decimal zeroes */
        digitToString(d, digits, buffer, d != 0);
      }
    }

    /*
     * If requested, output a decimal point and all the digits that follow it.
     * We initially put out a multiple of DEC_DIGITS digits, then truncate if
     * needed.
     */
    if (scale > 0) {
      buffer.put('.');
      for (i = 0; i < scale; d++, i += DEC_DIGITS) {
        digitToString(d, digits, buffer, true);
      }
    }

    /*
     * terminate the string and return it
     */
    int extra = (i - scale) % DEC_DIGITS;
    return new String(buffer.array(), 0, buffer.position() - extra);
  }

  /**
   * Convert a variable length array of bytes to an integer
   * @param bytes array of bytes that can be decoded as an integer
   * @return integer
   */
  public static Number numeric(byte [] bytes) {
    return numeric(bytes, 0, bytes.length);
  }

  /**
   * Convert a variable length array of bytes to an integer
   * @param bytes array of bytes that can be decoded as an integer
   * @param pos index of the start position of the bytes array for number
   * @param numBytes number of bytes to use, length is already encoded
   *                in the binary format but this is used for double checking
   * @return integer
   */
  public static Number numeric(byte [] bytes, int pos, int numBytes) {
    if (numBytes < 8) {
      throw new IllegalArgumentException("number of bytes should be at-least 8");
    }

    short len = ByteConverter.int2(bytes, pos);
    short weight = ByteConverter.int2(bytes, pos + 2);
    short sign = ByteConverter.int2(bytes, pos + 4);
    short scale = ByteConverter.int2(bytes, pos + 6);

    if (numBytes != (len * SHORT_BYTES + 8)) {
      throw new IllegalArgumentException("invalid length of bytes \"numeric\" value");
    }

    if (!(sign == NUMERIC_POS
        || sign == NUMERIC_NEG
        || sign == NUMERIC_NAN)) {
      throw new IllegalArgumentException("invalid sign in \"numeric\" value");
    }

    if (sign == NUMERIC_NAN) {
      return Double.NaN;
    }

    if ((scale & NUMERIC_DSCALE_MASK) != scale) {
      throw new IllegalArgumentException("invalid scale in \"numeric\" value");
    }

    short[] digits = new short[len];
    int idx = pos + 8;
    for (int i = 0; i < len; i++) {
      short d = ByteConverter.int2(bytes, idx);
      idx += 2;

      if (d < 0 || d >= NBASE) {
        throw new IllegalArgumentException("invalid digit in \"numeric\" value");
      }

      digits[i] = d;
    }

    String numString = numberBytesToString(digits, scale, weight, sign);
    return new BigDecimal(numString);
  }

  /**
   * Parses a long value from the byte array.
   *
   * @param bytes The byte array to parse.
   * @param idx The starting index of the parse in the byte array.
   * @return parsed long value.
   */
  public static long int8(byte[] bytes, int idx) {
    return
        ((long) (bytes[idx + 0] & 255) << 56)
            + ((long) (bytes[idx + 1] & 255) << 48)
            + ((long) (bytes[idx + 2] & 255) << 40)
            + ((long) (bytes[idx + 3] & 255) << 32)
            + ((long) (bytes[idx + 4] & 255) << 24)
            + ((long) (bytes[idx + 5] & 255) << 16)
            + ((long) (bytes[idx + 6] & 255) << 8)
            + (bytes[idx + 7] & 255);
  }

  /**
   * Parses an int value from the byte array.
   *
   * @param bytes The byte array to parse.
   * @param idx The starting index of the parse in the byte array.
   * @return parsed int value.
   */
  public static int int4(byte[] bytes, int idx) {
    return
        ((bytes[idx] & 255) << 24)
            + ((bytes[idx + 1] & 255) << 16)
            + ((bytes[idx + 2] & 255) << 8)
            + ((bytes[idx + 3] & 255));
  }

  /**
   * Parses a short value from the byte array.
   *
   * @param bytes The byte array to parse.
   * @param idx The starting index of the parse in the byte array.
   * @return parsed short value.
   */
  public static short int2(byte[] bytes, int idx) {
    return (short) (((bytes[idx] & 255) << 8) + ((bytes[idx + 1] & 255)));
  }

  /**
   * Parses a boolean value from the byte array.
   *
   * @param bytes
   *          The byte array to parse.
   * @param idx
   *          The starting index to read from bytes.
   * @return parsed boolean value.
   */
  public static boolean bool(byte[] bytes, int idx) {
    return bytes[idx] == 1;
  }

  /**
   * Parses a float value from the byte array.
   *
   * @param bytes The byte array to parse.
   * @param idx The starting index of the parse in the byte array.
   * @return parsed float value.
   */
  public static float float4(byte[] bytes, int idx) {
    return Float.intBitsToFloat(int4(bytes, idx));
  }

  /**
   * Parses a double value from the byte array.
   *
   * @param bytes The byte array to parse.
   * @param idx The starting index of the parse in the byte array.
   * @return parsed double value.
   */
  public static double float8(byte[] bytes, int idx) {
    return Double.longBitsToDouble(int8(bytes, idx));
  }

  /**
   * Encodes a long value to the byte array.
   *
   * @param target The byte array to encode to.
   * @param idx The starting index in the byte array.
   * @param value The value to encode.
   */
  public static void int8(byte[] target, int idx, long value) {
    target[idx + 0] = (byte) (value >>> 56);
    target[idx + 1] = (byte) (value >>> 48);
    target[idx + 2] = (byte) (value >>> 40);
    target[idx + 3] = (byte) (value >>> 32);
    target[idx + 4] = (byte) (value >>> 24);
    target[idx + 5] = (byte) (value >>> 16);
    target[idx + 6] = (byte) (value >>> 8);
    target[idx + 7] = (byte) value;
  }

  /**
   * Encodes a int value to the byte array.
   *
   * @param target The byte array to encode to.
   * @param idx The starting index in the byte array.
   * @param value The value to encode.
   */
  public static void int4(byte[] target, int idx, int value) {
    target[idx + 0] = (byte) (value >>> 24);
    target[idx + 1] = (byte) (value >>> 16);
    target[idx + 2] = (byte) (value >>> 8);
    target[idx + 3] = (byte) value;
  }

  /**
   * Encodes a int value to the byte array.
   *
   * @param target The byte array to encode to.
   * @param idx The starting index in the byte array.
   * @param value The value to encode.
   */
  public static void int2(byte[] target, int idx, int value) {
    target[idx + 0] = (byte) (value >>> 8);
    target[idx + 1] = (byte) value;
  }

  /**
   * Encodes a boolean value to the byte array.
   *
   * @param target
   *          The byte array to encode to.
   * @param idx
   *          The starting index in the byte array.
   * @param value
   *          The value to encode.
   */
  public static void bool(byte[] target, int idx, boolean value) {
    target[idx] = value ? (byte) 1 : (byte) 0;
  }

  /**
   * Encodes a int value to the byte array.
   *
   * @param target The byte array to encode to.
   * @param idx The starting index in the byte array.
   * @param value The value to encode.
   */
  public static void float4(byte[] target, int idx, float value) {
    int4(target, idx, Float.floatToRawIntBits(value));
  }

  /**
   * Encodes a int value to the byte array.
   *
   * @param target The byte array to encode to.
   * @param idx The starting index in the byte array.
   * @param value The value to encode.
   */
  public static void float8(byte[] target, int idx, double value) {
    int8(target, idx, Double.doubleToRawLongBits(value));
  }
}
