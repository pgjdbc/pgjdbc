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
import org.postgresql.geometric.PGcircle;
import org.postgresql.geometric.PGpoint;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.Float8Text;
import org.postgresql.util.PGpointFormat;
import org.postgresql.util.PGtokenizer;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL circle type (a center point and a radius).
 */
public final class CircleCodec implements StreamingBinaryCodec, TextCodec {

  public static final CircleCodec INSTANCE = new CircleCodec();

  private CircleCodec() {
    // Singleton
  }

  @Override
  public String getPrimaryTypeName() {
    return "circle";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGcircle.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 24) {
      throw Exceptions.invalidBinaryLength("circle", length);
    }
    double[] xy = PGpointFormat.parseBinary(data, offset);
    double r = ByteConverter.float8(data, offset + 16);
    return new PGcircle(xy[0], xy[1], r);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    double[] xyr = toXyr(value);
    byte[] result = new byte[24];
    PGpointFormat.appendBinary(result, 0, xyr[0], xyr[1]);
    ByteConverter.float8(result, 16, xyr[2]);
    return result;
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    double[] xyr = toXyr(value);
    out.writeDouble(xyr[0]);
    out.writeDouble(xyr[1]);
    out.writeDouble(xyr[2]);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 24) {
      throw Exceptions.invalidBinaryLength("circle", length);
    }
    // Render the centre and radius in the server's float8 text form (1, not Java's 1.0) so getString
    // reads back the same value whether the circle arrived in binary or text.
    StringBuilder sb = new StringBuilder("<");
    PGpointFormat.appendServerText(sb, ByteConverter.float8(data, offset),
        ByteConverter.float8(data, offset + 8)).append(',');
    Float8Text.append(sb, ByteConverter.float8(data, offset + 16)).append('>');
    return sb.toString();
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGtokenizer t = new PGtokenizer(PGtokenizer.removeAngle(data), ',');
    try {
      if (t.getSize() != 2) {
        throw new NumberFormatException("expected a center point and a radius, got " + t.getSize() + " tokens");
      }
      double[] center = PGpointFormat.parseText(t.getToken(0));
      double radius = Double.parseDouble(t.getToken(1));
      return new PGcircle(center[0], center[1], radius);
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("circle", data, PSQLState.DATA_TYPE_MISMATCH, e);
    }
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    double[] xyr = toXyr(value);
    StringBuilder sb = new StringBuilder("<");
    PGpointFormat.appendText(sb, xyr[0], xyr[1]).append(',').append(xyr[2]).append('>');
    return sb.toString();
  }

  private static double[] toXyr(Object value) throws SQLException {
    if (value instanceof PGcircle) {
      PGpoint center = ((PGcircle) value).center;
      if (center != null) {
        return new double[] {center.x, center.y, ((PGcircle) value).radius};
      }
    }
    throw Exceptions.cannotEncodeAs(value, "circle");
  }
}
