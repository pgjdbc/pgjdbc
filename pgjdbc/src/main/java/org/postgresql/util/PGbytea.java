/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.postgresql.core.FixedLengthOutputStream;
import org.postgresql.core.v3.SqlSerializationContext;

import org.checkerframework.checker.nullness.qual.PolyNull;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Converts to and from the postgresql bytea datatype used by the backend.
 */
public class PGbytea {

  /**
   * Lookup from an unsigned byte value to its hexadecimal digit value (0-15), or {@code -1} when the
   * byte is not an ASCII hex digit. Indexed by {@code b & 0xff}, so every input byte is in range.
   */
  private static final int[] HEX_VALS = new int[256];

  static {
    Arrays.fill(HEX_VALS, -1);
    for (int i = 0; i <= 9; i++) {
      HEX_VALS['0' + i] = i;
    }
    for (int i = 0; i < 6; i++) {
      HEX_VALS['A' + i] = 10 + i;
      HEX_VALS['a' + i] = 10 + i;
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

  private static byte[] toBytesHexEscaped(byte[] s) throws SQLException {
    // The leading "\x" is the format marker, so the hex digits start at index 2.
    final int realLength = s.length - 2;
    if ((realLength & 1) != 0) {
      throw new PSQLException(
          GT.tr("invalid hexadecimal data: odd number of digits"),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    byte[] output = new byte[realLength >>> 1];
    for (int i = 0; i < realLength; i += 2) {
      int val = (hexDigit(s[2 + i]) << 4) | hexDigit(s[3 + i]);
      output[i >>> 1] = (byte) val;
    }
    return output;
  }

  private static int hexDigit(byte b) throws SQLException {
    int val = HEX_VALS[b & 0xff];
    if (val < 0) {
      throw new PSQLException(
          GT.tr("invalid hexadecimal digit: \"{0}\"", (char) (b & 0xff)),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    return val;
  }

  private static byte[] toBytesOctalEscaped(byte[] s) throws SQLException {
    final int slength = s.length;
    // A backslash escape shrinks the output, so slength is an upper bound; the result is trimmed once
    // the exact length is known.
    final byte[] buf = new byte[slength];
    int bufpos = 0;
    for (int i = 0; i < slength; i++) {
      byte current = s[i];
      if (current != '\\') {
        buf[bufpos++] = current;
        continue;
      }
      // An escape is either "\\" (a literal backslash) or "\ooo" (three octal digits).
      if (i + 1 >= slength) {
        throw invalidByteaLiteral();
      }
      byte second = s[++i];
      if (second == '\\') {
        buf[bufpos++] = '\\';
        continue;
      }
      if (i + 2 >= slength) {
        throw invalidByteaLiteral();
      }
      int value = (octalDigit(second) << 6) | (octalDigit(s[++i]) << 3) | octalDigit(s[++i]);
      if (value > 0xff) {
        throw invalidByteaLiteral();
      }
      buf[bufpos++] = (byte) value;
    }
    return bufpos == slength ? buf : Arrays.copyOf(buf, bufpos);
  }

  private static int octalDigit(byte b) throws SQLException {
    if (b < '0' || b > '7') {
      throw invalidByteaLiteral();
    }
    return b - '0';
  }

  private static PSQLException invalidByteaLiteral() {
    return new PSQLException(
        GT.tr("invalid input syntax for type bytea"),
        PSQLState.INVALID_TEXT_REPRESENTATION);
  }

  /*
   * Converts a java byte[] into a PG bytea string (i.e. the text representation of the bytea data
   * type)
   */
  public static @PolyNull String toPGString(byte @PolyNull[] buf) {
    if (buf == null) {
      return null;
    }
    StringBuilder stringBuilder = new StringBuilder(2 + 2 * buf.length);
    stringBuilder.append("\\x");
    appendHexString(stringBuilder, buf, 0, buf.length);
    return stringBuilder.toString();
  }

  /**
   * Appends given byte array as hex string.
   * See HexEncodingBenchmark for the benchmark.
   * @param sb output builder
   * @param buf buffer to append
   * @param offset offset within the buffer
   * @param length the length of sequence to append
   */
  public static void appendHexString(StringBuilder sb, byte[] buf, int offset, int length) {
    for (int i = offset; i < offset + length; i++) {
      byte element = buf[i];
      sb.append(Character.forDigit((element >> 4) & 0xf, 16));
      sb.append(Character.forDigit(element & 0xf, 16));
    }
  }

  /**
   * Formats input object as {@code bytea} literal like {@code '\xcafebabe'::bytea}.
   * The following inputs are supported: {@code byte[]}, {@link StreamWrapper}, and
   * {@link ByteStreamWriter}.
   * @param value input value to format
   * @return formatted value
   * @throws IOException in case there's underflow in the input value
   * @deprecated prefer {@link #toPGLiteral(Object, SqlSerializationContext)} to clarify the behaviour
   *     regarding {@link InputStream} objects
   */
  @Deprecated
  public static String toPGLiteral(Object value) throws IOException {
    return toPGLiteral(value, SqlSerializationContext.of(true, true));
  }

  /**
   * Formats input object as {@code bytea} literal like {@code '\xcafebabe'::bytea}.
   * The following inputs are supported: {@code byte[]}, {@link StreamWrapper}, and
   * {@link ByteStreamWriter}.
   * @param value input value to format
   * @param context specifies configuration for converting the parameters to string
   * @return formatted value
   * @throws IOException in case there's underflow in the input value
   */
  public static String toPGLiteral(Object value, SqlSerializationContext context) throws IOException {
    if (value instanceof String) {
      // A bytea value supplied as text (for example via PGobject) in the hex
      // format: \x followed by hex digit pairs, with whitespace allowed between
      // them. Validate the digits so a malformed value fails fast and cannot
      // smuggle a quote or backslash into the literal. The escape format is
      // handled by the caller, which quotes it like any other text literal.
      String str = (String) value;
      if (str.length() < 2 || str.charAt(0) != '\\' || str.charAt(1) != 'x') {
        throw new IllegalArgumentException(
            GT.tr("A bytea value passed as a String must be in hex format, such as \\x1a2b."));
      }
      int digits = 0;
      for (int i = 2; i < str.length(); i++) {
        char ch = str.charAt(i);
        if (isHexDigit(ch)) {
          digits++;
        } else if (!isByteaHexWhitespace(ch)) {
          throw new IllegalArgumentException(
              GT.tr("Invalid character {0} at index {1} in bytea hex value.", ch, i));
        }
      }
      if ((digits & 1) != 0) {
        throw new IllegalArgumentException(
            GT.tr("The bytea hex value has an odd number of digits."));
      }
      return "'" + str + "'::bytea";
    }

    if (value instanceof byte[]) {
      byte[] bytes = (byte[]) value;
      StringBuilder sb = new StringBuilder(bytes.length * 2 + 11);
      sb.append("'\\x");
      appendHexString(sb, bytes, 0, bytes.length);
      sb.append("'::bytea");
      return sb.toString();
    }

    if (value instanceof StreamWrapper) {
      StreamWrapper sw = (StreamWrapper) value;
      byte[] bytes = sw.getBytes();
      if (context.getIdempotent() && bytes == null) {
        // Note: we skip reading the stream wrapper only in case it wraps a stream
        // If StreamWrapper wraps a byte[] instance, then it is fine to serialize it
        return "?";
      }

      int length = sw.getLength();
      StringBuilder sb = new StringBuilder(length * 2 + 11);
      sb.append("'\\x");
      if (bytes != null) {
        appendHexString(sb, bytes, sw.getOffset(), length);
      } else if (length > 0) {
        InputStream str = sw.getStream();
        byte[] streamBuffer = new byte[8192];
        int read;
        while (length > 0) {
          read = str.read(streamBuffer, 0, Math.min(length, streamBuffer.length));
          if (read == -1) {
            break;
          }
          appendHexString(sb, streamBuffer, 0, read);
          length -= read;
        }
        if (length > 0) {
          throw new EOFException(
              GT.tr("Premature end of input stream, expected {0} bytes, but only read {1}.",
                  sw.getLength(), sw.getLength() - length));
        }
      }
      sb.append("'::bytea");
      return sb.toString();
    }

    if (value instanceof ByteStreamWriter) {
      ByteStreamWriter bsw = (ByteStreamWriter) value;
      int len = bsw.getLength();
      StringBuilder sb = new StringBuilder(len * 2 + 11);
      sb.append("'\\x");
      FixedLengthOutputStream str = new FixedLengthOutputStream(len, new OutputStream() {
        @Override
        public void write(int b) {
          sb.append(Character.forDigit((b >> 4) & 0xf, 16));
          sb.append(Character.forDigit(b & 0xf, 16));
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
          appendHexString(sb, b, off, len);
        }
      });
      bsw.writeTo(() -> str);
      for (int i = 0; i < str.remaining(); i++) {
        sb.append("00");
      }
      sb.append("'::bytea");
      return sb.toString();
    }

    throw new IllegalArgumentException(
        GT.tr("Cannot convert {0} to {1} literal", value.getClass(), "bytea"));
  }

  private static boolean isHexDigit(char ch) {
    return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
  }

  private static boolean isByteaHexWhitespace(char ch) {
    // PostgreSQL ignores exactly these whitespace characters between hex digit
    // pairs. Form feed and vertical tab are not ignored, so they are rejected.
    return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r';
  }
}
