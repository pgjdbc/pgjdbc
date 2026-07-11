/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.geometric.PGline;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.Float8Text;
import org.postgresql.util.PGtokenizer;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL line type ({@code Ax + By + C = 0}).
 *
 * <p>{@code line_out} always emits the {@code {A,B,C}} form, so that is the only text form this
 * codec reads; {@link PGline#setValue(String)} additionally accepts a two-point {@code [(x1,y1),
 * (x2,y2)]} form as a convenience for hand-built values, which is unrelated to the wire format
 * and stays there.</p>
 */
public final class LineCodec implements StreamingBinaryCodec, TextCodec {

  public static final LineCodec INSTANCE = new LineCodec();

  private LineCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "line";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGline.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 24) {
      throw Exceptions.invalidBinaryLength("line", length);
    }
    double a = ByteConverter.float8(data, offset);
    double b = ByteConverter.float8(data, offset + 8);
    double c = ByteConverter.float8(data, offset + 16);
    return new PGline(a, b, c);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGline line = toLine(value);
    byte[] result = new byte[24];
    ByteConverter.float8(result, 0, line.a);
    ByteConverter.float8(result, 8, line.b);
    ByteConverter.float8(result, 16, line.c);
    return result;
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    PGline line = toLine(value);
    out.writeDouble(line.a);
    out.writeDouble(line.b);
    out.writeDouble(line.c);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 24) {
      throw Exceptions.invalidBinaryLength("line", length);
    }
    // Render the coefficients in the server's float8 text form (1, not Java's 1.0) so getString reads
    // back the same value whether the line arrived in binary or text.
    StringBuilder sb = new StringBuilder("{");
    Float8Text.append(sb, ByteConverter.float8(data, offset)).append(',');
    Float8Text.append(sb, ByteConverter.float8(data, offset + 8)).append(',');
    Float8Text.append(sb, ByteConverter.float8(data, offset + 16)).append('}');
    return sb.toString();
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGtokenizer t = new PGtokenizer(PGtokenizer.removeCurlyBrace(data), ',');
    try {
      if (t.getSize() != 3) {
        throw new NumberFormatException("expected 3 coefficients, got " + t.getSize());
      }
      double a = Double.parseDouble(t.getToken(0));
      double b = Double.parseDouble(t.getToken(1));
      double c = Double.parseDouble(t.getToken(2));
      return new PGline(a, b, c);
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("line", data, PSQLState.DATA_TYPE_MISMATCH, e);
    }
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGline line = toLine(value);
    return "{" + line.a + "," + line.b + "," + line.c + "}";
  }

  private static PGline toLine(Object value) throws SQLException {
    if (value instanceof PGline) {
      return (PGline) value;
    }
    throw Exceptions.cannotEncodeAs(value, "line");
  }
}
