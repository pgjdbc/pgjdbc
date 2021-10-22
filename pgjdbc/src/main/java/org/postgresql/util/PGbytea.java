/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.PolyNull;

import java.sql.SQLException;

/**
 * Converts to and from the postgresql bytea datatype used by the backend.
 */
public class PGbytea {
  private static final int MAX_3_BUFF_SIZE = 2 * 1024 * 1024;

  /**
   * Lookup table for each of the valid ascii code points (offset by {@code '0'})
   * to the 4 bit numeric value.
   */
  private static final int[] HEX_VALS = new int['f' + 1 - '0'];

  static {
    for (int i = 0; i < 10; ++i) {
      HEX_VALS[i] = (byte) i;
    }
    for (int i = 0; i < 6; ++i) {
      HEX_VALS['A' + i - '0'] = (byte) (10 + i);
      HEX_VALS['a' + i - '0'] = (byte) (10 + i);
    }
  }

  /*
   * Converts a PG bytea raw value (i.e. the raw binary representation of the bytea data type) into
   * a java byte[]
   */
  public static byte @PolyNull [] toBytes(byte @PolyNull[] s) throws SQLException {
    if (s == null) {
      return null;
    }

    // Starting with PG 9.0, a new hex format is supported
    // that starts with "\x". Figure out which format we're
    // dealing with here.
    //
    if (s.length < 2 || s[0] != '\\' || s[1] != 'x') {
      return toBytesOctalEscaped(s);
    }
    return toBytesHexEscaped(s);
  }

  private static byte[] toBytesHexEscaped(byte[] s) {
    // first 2 bytes of s indicate the byte[] is hex encoded
    // so they need to be ignored here
    final int realLength = s.length - 2;
    byte[] output = new byte[realLength >>> 1];
    for (int i = 0; i < realLength; i += 2) {
      int val = getHex(s[2 + i]) << 4;
      val |= getHex(s[3 + i]);
      output[i >>> 1] = (byte) val;
    }
    return output;
  }

  private static int getHex(byte b) {
    return HEX_VALS[b - '0'];
  }

  private static byte[] toBytesOctalEscaped(byte[] s) {
    final int slength = s.length;
    byte[] buf = null;
    int correctSize = slength;
    if (slength > MAX_3_BUFF_SIZE) {
      // count backslash escapes, they will be either
      // backslashes or an octal escape \\ or \003
      //
      for (int i = 0; i < slength; ++i) {
        byte current = s[i];
        if (current == '\\') {
          byte next = s[++i];
          if (next == '\\') {
            --correctSize;
          } else {
            correctSize -= 3;
          }
        }
      }
      buf = new byte[correctSize];
    } else {
      buf = new byte[slength];
    }
    int bufpos = 0;
    int thebyte;
    byte nextbyte;
    byte secondbyte;
    for (int i = 0; i < slength; i++) {
      nextbyte = s[i];
      if (nextbyte == (byte) '\\') {
        secondbyte = s[++i];
        if (secondbyte == (byte) '\\') {
          // escaped \
          buf[bufpos++] = (byte) '\\';
        } else {
          thebyte = (secondbyte - 48) * 64 + (s[++i] - 48) * 8 + (s[++i] - 48);
          if (thebyte > 127) {
            thebyte -= 256;
          }
          buf[bufpos++] = (byte) thebyte;
        }
      } else {
        buf[bufpos++] = nextbyte;
      }
    }
    if (bufpos == correctSize) {
      return buf;
    }
    byte[] result = new byte[bufpos];
    System.arraycopy(buf, 0, result, 0, bufpos);
    return result;
  }

  /*
   * Converts a java byte[] into a PG bytea string (i.e. the text representation of the bytea data
   * type)
   */
  public static @PolyNull String toPGString(byte @PolyNull[] buf) {
    if (buf == null) {
      return null;
    }
    StringBuilder stringBuilder = new StringBuilder(2 * buf.length);
    for (byte element : buf) {
      int elementAsInt = (int) element;
      if (elementAsInt < 0) {
        elementAsInt = 256 + elementAsInt;
      }
      // we escape the same non-printable characters as the backend
      // we must escape all 8bit characters otherwise when convering
      // from java unicode to the db character set we may end up with
      // question marks if the character set is SQL_ASCII
      if (elementAsInt < 040 || elementAsInt > 0176) {
        // escape charcter with the form \000, but need two \\ because of
        // the Java parser
        stringBuilder.append("\\");
        stringBuilder.append((char) (((elementAsInt >> 6) & 0x3) + 48));
        stringBuilder.append((char) (((elementAsInt >> 3) & 0x7) + 48));
        stringBuilder.append((char) ((elementAsInt & 0x07) + 48));
      } else if (element == (byte) '\\') {
        // escape the backslash character as \\, but need four \\\\ because
        // of the Java parser
        stringBuilder.append("\\\\");
      } else {
        // other characters are left alone
        stringBuilder.append((char) element);
      }
    }
    return stringBuilder.toString();
  }
}
