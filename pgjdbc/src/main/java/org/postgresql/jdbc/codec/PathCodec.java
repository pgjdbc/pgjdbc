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
import org.postgresql.geometric.PGpath;
import org.postgresql.geometric.PGpoint;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PGpointFormat;
import org.postgresql.util.PGtokenizer;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL path type (an open or closed sequence of points).
 *
 * <p>Binary wire form: a 1-byte closed flag, a big-endian {@code int32} point count, then that
 * many {@code (x,y)} {@code float8} pairs — see {@code path_recv}/{@code path_send}.</p>
 */
public final class PathCodec implements StreamingBinaryCodec, TextCodec {

  public static final PathCodec INSTANCE = new PathCodec();

  private PathCodec() {
    // Singleton
  }

  @Override
  public String getPrimaryTypeName() {
    return "path";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGpath.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length < 5) {
      throw Exceptions.invalidBinaryLength("path", length);
    }
    boolean closed = data[offset] != 0;
    int npts = ByteConverter.int4(data, offset + 1);
    if (npts < 0 || length != 5 + npts * 16L) {
      throw Exceptions.invalidBinaryLength("path", length);
    }
    PGpoint[] points = new PGpoint[npts];
    int pos = offset + 5;
    for (int i = 0; i < npts; i++) {
      double[] xy = PGpointFormat.parseBinary(data, pos);
      points[i] = new PGpoint(xy[0], xy[1]);
      pos += 16;
    }
    return new PGpath(points, !closed);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGpath path = toPath(value);
    PGpoint[] points = points(path);
    byte[] result = new byte[5 + points.length * 16];
    result[0] = (byte) (path.open ? 0 : 1);
    ByteConverter.int4(result, 1, points.length);
    int pos = 5;
    for (PGpoint point : points) {
      PGpointFormat.appendBinary(result, pos, point.x, point.y);
      pos += 16;
    }
    return result;
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    PGpath path = toPath(value);
    PGpoint[] points = points(path);
    out.writeByte(path.open ? 0 : 1);
    out.writeInt32(points.length);
    for (PGpoint point : points) {
      out.writeDouble(point.x);
      out.writeDouble(point.y);
    }
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length < 5) {
      throw Exceptions.invalidBinaryLength("path", length);
    }
    boolean closed = data[offset] != 0;
    int npts = ByteConverter.int4(data, offset + 1);
    if (npts < 0 || length != 5 + npts * 16L) {
      throw Exceptions.invalidBinaryLength("path", length);
    }
    // Render the vertices in the server's float8 text form (1, not Java's 1.0) so getString reads back
    // the same value whether the path arrived in binary or text; the open/closed brackets ride the wire.
    StringBuilder sb = new StringBuilder(closed ? "(" : "[");
    int pos = offset + 5;
    for (int i = 0; i < npts; i++) {
      if (i > 0) {
        sb.append(',');
      }
      PGpointFormat.appendServerText(sb, ByteConverter.float8(data, pos),
          ByteConverter.float8(data, pos + 8));
      pos += 16;
    }
    return sb.append(closed ? ')' : ']').toString();
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    boolean open;
    String inner;
    if (data.startsWith("[") && data.endsWith("]")) {
      open = true;
      inner = PGtokenizer.removeBox(data);
    } else if (data.startsWith("(") && data.endsWith(")")) {
      open = false;
      inner = PGtokenizer.removePara(data);
    } else {
      throw new PSQLException(GT.tr("Cannot tell if path is open or closed: {0}.", data),
          PSQLState.DATA_TYPE_MISMATCH);
    }
    PGtokenizer t = new PGtokenizer(inner, ',');
    int npoints = t.getSize();
    PGpoint[] points = new PGpoint[npoints];
    for (int i = 0; i < npoints; i++) {
      double[] xy = PGpointFormat.parseText(t.getToken(i));
      points[i] = new PGpoint(xy[0], xy[1]);
    }
    return new PGpath(points, open);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGpath path = toPath(value);
    PGpoint[] points = points(path);
    StringBuilder sb = new StringBuilder(path.open ? "[" : "(");
    for (int i = 0; i < points.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      PGpointFormat.appendText(sb, points[i].x, points[i].y);
    }
    sb.append(path.open ? ']' : ')');
    return sb.toString();
  }

  private static PGpath toPath(Object value) throws SQLException {
    if (value instanceof PGpath) {
      return (PGpath) value;
    }
    throw Exceptions.cannotEncodeAs(value, "path");
  }

  private static PGpoint[] points(PGpath path) throws SQLException {
    PGpoint[] points = path.points;
    if (points == null) {
      throw Exceptions.cannotEncodeAs(path, "path");
    }
    return points;
  }
}
