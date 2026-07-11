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
import org.postgresql.geometric.PGlseg;
import org.postgresql.geometric.PGpoint;
import org.postgresql.util.PGpointFormat;
import org.postgresql.util.PGtokenizer;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL lseg (line segment) type.
 */
public final class LsegCodec implements StreamingBinaryCodec, TextCodec {

  public static final LsegCodec INSTANCE = new LsegCodec();

  private LsegCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "lseg";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGlseg.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 32) {
      throw Exceptions.invalidBinaryLength("lseg", length);
    }
    double[] xy1 = PGpointFormat.parseBinary(data, offset);
    double[] xy2 = PGpointFormat.parseBinary(data, offset + 16);
    return new PGlseg(new PGpoint(xy1[0], xy1[1]), new PGpoint(xy2[0], xy2[1]));
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGpoint[] point = toPoints(value);
    byte[] result = new byte[32];
    PGpointFormat.appendBinary(result, 0, point[0].x, point[0].y);
    PGpointFormat.appendBinary(result, 16, point[1].x, point[1].y);
    return result;
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    PGpoint[] point = toPoints(value);
    out.writeDouble(point[0].x);
    out.writeDouble(point[0].y);
    out.writeDouble(point[1].x);
    out.writeDouble(point[1].y);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 32) {
      throw Exceptions.invalidBinaryLength("lseg", length);
    }
    // Render the endpoints in the server's float8 text form (1, not Java's 1.0) so getString reads
    // back the same value whether the segment arrived in binary or text.
    double[] xy1 = PGpointFormat.parseBinary(data, offset);
    double[] xy2 = PGpointFormat.parseBinary(data, offset + 16);
    StringBuilder sb = new StringBuilder("[");
    PGpointFormat.appendServerText(sb, xy1[0], xy1[1]).append(',');
    PGpointFormat.appendServerText(sb, xy2[0], xy2[1]).append(']');
    return sb.toString();
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGtokenizer t = new PGtokenizer(PGtokenizer.removeBox(data), ',');
    try {
      if (t.getSize() != 2) {
        throw new NumberFormatException("expected 2 points, got " + t.getSize());
      }
      double[] p1 = PGpointFormat.parseText(t.getToken(0));
      double[] p2 = PGpointFormat.parseText(t.getToken(1));
      return new PGlseg(new PGpoint(p1[0], p1[1]), new PGpoint(p2[0], p2[1]));
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("lseg", data, PSQLState.DATA_TYPE_MISMATCH, e);
    }
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGpoint[] point = toPoints(value);
    StringBuilder sb = new StringBuilder("[");
    PGpointFormat.appendText(sb, point[0].x, point[0].y).append(',');
    PGpointFormat.appendText(sb, point[1].x, point[1].y).append(']');
    return sb.toString();
  }

  private static PGpoint[] toPoints(Object value) throws SQLException {
    if (!(value instanceof PGlseg)) {
      throw Exceptions.cannotEncodeAs(value, "lseg");
    }
    PGpoint[] point = ((PGlseg) value).point;
    if (point == null) {
      throw Exceptions.cannotEncodeAs(value, "lseg");
    }
    return point;
  }
}
