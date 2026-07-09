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
import org.postgresql.geometric.PGpoint;
import org.postgresql.util.PGpointFormat;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL point type.
 */
public final class PointCodec implements StreamingBinaryCodec, TextCodec {

  public static final PointCodec INSTANCE = new PointCodec();

  private PointCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "point";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGpoint.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 16) {
      throw Exceptions.invalidBinaryLength("point", length);
    }
    double[] xy = PGpointFormat.parseBinary(data, offset);
    return new PGpoint(xy[0], xy[1]);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGpoint point = toPoint(value);
    byte[] result = new byte[16];
    PGpointFormat.appendBinary(result, 0, point.x, point.y);
    return result;
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    PGpoint point = toPoint(value);
    out.writeDouble(point.x);
    out.writeDouble(point.y);
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    double[] xy = PGpointFormat.parseText(data);
    return new PGpoint(xy[0], xy[1]);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGpoint point = toPoint(value);
    return PGpointFormat.appendText(new StringBuilder(), point.x, point.y).toString();
  }

  static PGpoint toPoint(Object value) throws SQLException {
    if (value instanceof PGpoint) {
      return (PGpoint) value;
    }
    throw Exceptions.cannotEncodeAs(value, "point");
  }
}
