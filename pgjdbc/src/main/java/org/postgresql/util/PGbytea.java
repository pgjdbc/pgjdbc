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

/**
 * Converts to and from the postgresql bytea datatype used by the backend.
 */
public class PGbytea {
  private static final int MAX_3_BUFF_SIZE = 2 * 1024 * 1024;

  static final byte[] HEX_LOOKUP = new byte[256];

  static {
    // Initialize lookup table for hex characters
    for (char c = '0'; c <= '9'; c++) {
      HEX_LOOKUP[c] = 1;
    }
    for (char c = 'a'; c <= 'f'; c++) {
      HEX_LOOKUP[c] = 1;
    }
    for (char c = 'A'; c <= 'F'; c++) {
      HEX_LOOKUP[c] = 1;
    }
  }

  /**
   * Lookup table for each of the valid ascii code points (offset by {@code '0'})
   * to the 4 bit numeric value.
   */
  private static final int[] HEX_VALS = new int['f' + 1 - '0'];

  static {
    for (int i = 0; i < 10; i++) {
      HEX_VALS[i] = (byte) i;
    }
    for (int i = 0; i < 6; i++) {
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
      for (int i = 0; i < slength; i++) {
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
      final String str = (String) value;
      final int length = str.length();

      // Fast fail if string is too short
      if (length < 2 || str.charAt(0) != '\\' || str.charAt(1) != 'x') {
        throw new IllegalArgumentException(GT.tr("bytea string parameters must be hex format"));
      }

      // Pre-calculate capacity: prefix('\x') + actual hex digits + suffix('::bytea')
      final StringBuilder sb = new StringBuilder(length + 7);  // Conservative estimate
      sb.append("'\\x");

      int i = 2;
      while (i < length) {
        char c = str.charAt(i);

        // Skip whitespace using bitwise operation
        if ((c <= ' ' && ((1L << c) & ((1L << ' ') | (1L << '\t') | (1L << '\r') | (1L << '\n'))) != 0)) {
          i++;
          continue;
        }

        // Check if we have enough characters left
        if (i + 2 > length) {
          throw new IllegalArgumentException(GT.tr("Truncated bytea hex format"));
        }

        // Get hex digits
        final char c1 = c;
        final char c2 = str.charAt(i + 1);

        // Validate hex digits using lookup table
        if (c1 >= HEX_LOOKUP.length || HEX_LOOKUP[c1] == 0) {
          throw new IllegalArgumentException(GT.tr("Invalid bytea hex format character {0}", c1));
        }
        if (c2 >= HEX_LOOKUP.length || HEX_LOOKUP[c2] == 0) {
          throw new IllegalArgumentException(GT.tr("Invalid bytea hex format character {0}", c2));
        }

        sb.append(str,i,i + 1);
        i += 2;
      }

      sb.append("'::bytea");
      return sb.toString();
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
}
