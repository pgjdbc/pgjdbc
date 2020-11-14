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
    byte[] output = new byte[(s.length - 2) / 2];
    for (int i = 0; i < output.length; i++) {
      byte b1 = gethex(s[2 + i * 2]);
      byte b2 = gethex(s[2 + i * 2 + 1]);
      // squid:S3034
      // Raw byte values should not be used in bitwise operations in combination with shifts
      output[i] = (byte) ((b1 << 4) | (b2 & 0xff));
    }
    return output;
  }

  private static byte gethex(byte b) {
    // 0-9 == 48-57
    if (b <= 57) {
      return (byte) (b - 48);
    }

    // a-f == 97-102
    if (b >= 97) {
      return (byte) (b - 97 + 10);
    }

    // A-F == 65-70
    return (byte) (b - 65 + 10);
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
