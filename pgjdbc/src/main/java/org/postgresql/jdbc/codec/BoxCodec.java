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
import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGpoint;
import org.postgresql.util.PGpointFormat;
import org.postgresql.util.PGtokenizer;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL box type.
 *
 * <p>PostgreSQL normalizes a box's two corners to {@code (high.x, high.y), (low.x, low.y)} on
 * both {@code box_in}/{@code box_out} and {@code box_recv}/{@code box_send} regardless of the
 * corner order it was given, so this codec normalizes on both decode and encode to match.</p>
 */
public final class BoxCodec implements StreamingBinaryCodec, TextCodec {

  public static final BoxCodec INSTANCE = new BoxCodec();

  private BoxCodec() {
    // Singleton
  }

  @Override
  public String getPrimaryTypeName() {
    return "box";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGbox.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 32) {
      throw Exceptions.invalidBinaryLength("box", length);
    }
    double[] p1 = PGpointFormat.parseBinary(data, offset);
    double[] p2 = PGpointFormat.parseBinary(data, offset + 16);
    return normalize(p1[0], p1[1], p2[0], p2[1]);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGpoint[] hiLo = normalizedCorners(value);
    byte[] result = new byte[32];
    PGpointFormat.appendBinary(result, 0, hiLo[0].x, hiLo[0].y);
    PGpointFormat.appendBinary(result, 16, hiLo[1].x, hiLo[1].y);
    return result;
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    PGpoint[] hiLo = normalizedCorners(value);
    out.writeDouble(hiLo[0].x);
    out.writeDouble(hiLo[0].y);
    out.writeDouble(hiLo[1].x);
    out.writeDouble(hiLo[1].y);
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGtokenizer t = new PGtokenizer(data, ',');
    try {
      if (t.getSize() != 2) {
        throw new NumberFormatException("expected 2 points, got " + t.getSize());
      }
      double[] p1 = PGpointFormat.parseText(t.getToken(0));
      double[] p2 = PGpointFormat.parseText(t.getToken(1));
      return normalize(p1[0], p1[1], p2[0], p2[1]);
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("box", data, PSQLState.DATA_TYPE_MISMATCH, e);
    }
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGpoint[] hiLo = normalizedCorners(value);
    StringBuilder sb = new StringBuilder();
    PGpointFormat.appendText(sb, hiLo[0].x, hiLo[0].y).append(',');
    PGpointFormat.appendText(sb, hiLo[1].x, hiLo[1].y);
    return sb.toString();
  }

  private static PGbox normalize(double x1, double y1, double x2, double y2) {
    PGpoint hi = new PGpoint(Math.max(x1, x2), Math.max(y1, y2));
    PGpoint lo = new PGpoint(Math.min(x1, x2), Math.min(y1, y2));
    return new PGbox(hi, lo);
  }

  private static PGpoint[] normalizedCorners(Object value) throws SQLException {
    if (!(value instanceof PGbox)) {
      throw Exceptions.cannotEncodeAs(value, "box");
    }
    PGpoint[] point = ((PGbox) value).point;
    if (point == null) {
      throw Exceptions.cannotEncodeAs(value, "box");
    }
    PGpoint p1 = point[0];
    PGpoint p2 = point[1];
    return new PGpoint[] {
        new PGpoint(Math.max(p1.x, p2.x), Math.max(p1.y, p2.y)),
        new PGpoint(Math.min(p1.x, p2.x), Math.min(p1.y, p2.y)),
    };
  }
}
