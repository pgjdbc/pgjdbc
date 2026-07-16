/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TypeDescriptor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.Charset;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL {@code "char"} type (OID 18), the internal single-byte character type -- distinct
 * from {@code bpchar}/{@code character(n)}.
 *
 * <p>{@code "char"}'s two wire formats carry different bytes for the same value -- {@code charsend} emits the
 * raw byte while {@code charout} escapes it -- so this codec reproduces the server's {@code charout} /
 * {@code charin} text form on both the binary and the text side, giving the same driver value either way:</p>
 * <ul>
 *   <li>{@code 0x00} decodes to {@code ""} (and {@code ""} encodes back to {@code 0x00});</li>
 *   <li>a byte in {@code 0x01..0x7F} decodes to that one character;</li>
 *   <li>a byte in {@code 0x80..0xFF} decodes to a backslash-octal escape ({@code 0x80 -> "\200"}), and that
 *       escape encodes back to the byte, matching {@code charout}/{@code charin}.</li>
 * </ul>
 *
 * <p>The binary decode/encode mirror {@code charout}/{@code charin} for the one-byte wire; text is already
 * that form on the wire, so the base charset pass-through handles it. The server has {@code charsend} and
 * this codec decodes binary, so the driver receives {@code "char"} columns in binary by default (see
 * {@code TypeInfoCache.shouldReceiveBinary}). Binary parameter send stays opt-in via
 * {@code binaryTransferEnable} until {@code "char"} joins the send allow-list.</p>
 */
public final class CharCodec extends AbstractTextCodec {

  public static final CharCodec INSTANCE = new CharCodec();

  private CharCodec() {
    super("char");
  }

  @Override
  public Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    // The one-byte wire is charsend's raw byte; reproduce charout's text form so binary getString matches
    // the text wire (and the server's ::text). Other lengths never occur for a real value, so leave them
    // to the base charset decode rather than guessing.
    if (length == 1) {
      int b = data[offset] & 0xFF;
      if (b == 0) {
        return "";
      }
      if (b >= 0x80) {
        return octalEscape(b);
      }
    }
    return super.decodeBinary(data, offset, length, type, ctx);
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    // The base decodes straight to a String here without going through decodeBinary, so route the string
    // targets through the charout-faithful decodeBinary; other targets keep the base charset parse.
    if (targetClass == String.class || targetClass == Object.class) {
      return targetClass.cast(decodeBinary(data, offset, length, type, ctx));
    }
    return super.decodeBinaryAs(data, offset, length, type, targetClass, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // charsend is exactly one byte, so mirror charin: "" is '\0', a "\NNN" octal escape is that byte, and
    // anything else is the value's first byte. The generic charset encoder would emit the whole string.
    return new byte[]{charByte(toString(value), ctx.getCharset())};
  }

  /** {@code charout} for a high byte: a backslash followed by its three octal digits, e.g. {@code "\200"}. */
  private static String octalEscape(int b) {
    return new String(new char[]{'\\',
        (char) ('0' + ((b >> 6) & 7)), (char) ('0' + ((b >> 3) & 7)), (char) ('0' + (b & 7))});
  }

  /** {@code charin}: the single byte a string maps to -- empty is {@code 0}, a {@code \NNN} octal escape is
   * that byte (mod 256), and anything else is the string's first byte in the connection charset. */
  private static byte charByte(String s, Charset charset) {
    if (s.isEmpty()) {
      return 0;
    }
    if (s.length() >= 4 && s.charAt(0) == '\\') {
      char c1 = s.charAt(1);
      char c2 = s.charAt(2);
      char c3 = s.charAt(3);
      if (isOctalDigit(c1) && isOctalDigit(c2) && isOctalDigit(c3)) {
        int v = ((c1 - '0') << 6) | ((c2 - '0') << 3) | (c3 - '0');
        return (byte) v;
      }
    }
    byte[] bytes = s.getBytes(charset);
    return bytes.length == 0 ? 0 : bytes[0];
  }

  private static boolean isOctalDigit(char c) {
    return c >= '0' && c <= '7';
  }
}
