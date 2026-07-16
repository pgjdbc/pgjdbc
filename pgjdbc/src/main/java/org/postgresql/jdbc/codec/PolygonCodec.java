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
import org.postgresql.geometric.PGpolygon;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGpointFormat;
import org.postgresql.util.PGtokenizer;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL polygon type (an implicitly closed sequence of points).
 *
 * <p>Binary wire form: a big-endian {@code int32} point count, then that many {@code (x,y)}
 * {@code float8} pairs — see {@code poly_recv}/{@code poly_send}. Unlike {@code path}, there is
 * no closed flag (a polygon is always closed) and the bounding box is not sent; the server
 * recomputes it on receipt.</p>
 */
public final class PolygonCodec implements StreamingBinaryCodec, TextCodec {

  public static final PolygonCodec INSTANCE = new PolygonCodec();

  private PolygonCodec() {
    // Singleton
  }

  @Override
  public String getPrimaryTypeName() {
    return "polygon";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGpolygon.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length < 4) {
      throw Exceptions.invalidBinaryLength("polygon", length);
    }
    int npts = ByteConverter.int4(data, offset);
    if (npts < 0 || length != 4 + npts * 16L) {
      throw Exceptions.invalidBinaryLength("polygon", length);
    }
    PGpoint[] points = new PGpoint[npts];
    int pos = offset + 4;
    for (int i = 0; i < npts; i++) {
      double[] xy = PGpointFormat.parseBinary(data, pos);
      points[i] = new PGpoint(xy[0], xy[1]);
      pos += 16;
    }
    return new PGpolygon(points);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGpoint[] points = points(value);
    byte[] result = new byte[4 + points.length * 16];
    ByteConverter.int4(result, 0, points.length);
    int pos = 4;
    for (PGpoint point : points) {
      PGpointFormat.appendBinary(result, pos, point.x, point.y);
      pos += 16;
    }
    return result;
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    PGpoint[] points = points(value);
    out.writeInt32(points.length);
    for (PGpoint point : points) {
      out.writeDouble(point.x);
      out.writeDouble(point.y);
    }
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length < 4) {
      throw Exceptions.invalidBinaryLength("polygon", length);
    }
    int npts = ByteConverter.int4(data, offset);
    if (npts < 0 || length != 4 + npts * 16L) {
      throw Exceptions.invalidBinaryLength("polygon", length);
    }
    // Render the vertices in the server's float8 text form (1, not Java's 1.0) so getString reads back
    // the same value whether the polygon arrived in binary or text.
    StringBuilder sb = new StringBuilder("(");
    int pos = offset + 4;
    for (int i = 0; i < npts; i++) {
      if (i > 0) {
        sb.append(',');
      }
      PGpointFormat.appendServerText(sb, ByteConverter.float8(data, pos),
          ByteConverter.float8(data, pos + 8));
      pos += 16;
    }
    return sb.append(')').toString();
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGtokenizer t = new PGtokenizer(PGtokenizer.removePara(data), ',');
    int npoints = t.getSize();
    PGpoint[] points = new PGpoint[npoints];
    for (int i = 0; i < npoints; i++) {
      double[] xy = PGpointFormat.parseText(t.getToken(i));
      points[i] = new PGpoint(xy[0], xy[1]);
    }
    return new PGpolygon(points);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGpoint[] points = points(value);
    StringBuilder sb = new StringBuilder("(");
    for (int i = 0; i < points.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      PGpointFormat.appendText(sb, points[i].x, points[i].y);
    }
    sb.append(')');
    return sb.toString();
  }

  private static PGpoint[] points(Object value) throws SQLException {
    if (!(value instanceof PGpolygon)) {
      throw Exceptions.cannotEncodeAs(value, "polygon");
    }
    PGpoint[] points = ((PGpolygon) value).points;
    if (points == null) {
      throw Exceptions.cannotEncodeAs(value, "polygon");
    }
    return points;
  }
}
