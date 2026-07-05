/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.CodecDepth;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PGRange;
import org.postgresql.util.PGmultirange;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Codec for PostgreSQL multirange types (PostgreSQL 14+).
 *
 * <p>A multirange is an ordered list of non-overlapping {@link PGRange} values of one subtype, such
 * as {@code int4multirange}, {@code nummultirange}, or {@code tstzmultirange}. This codec composes
 * on top of {@link RangeCodec}: it owns the multirange framing (the {@code {...}} braces in text,
 * the count-and-length headers in binary) and delegates each range to the range codec, which
 * resolves the bound subtype in turn. The range type itself is taken from the multirange metadata
 * ({@code pg_range.rngtypid}, joined on {@code rngmultitypid}; see
 * {@link TypeDescriptor#getMultirangeRange()}).</p>
 *
 * <p>Text format: {@code {}} for an empty multirange, {@code {[1,5)}} for one range,
 * {@code {[1,5),[10,20)}} for several. The ranges are joined by commas; each is a plain
 * {@code range_out} literal, so a comma-containing bound is quoted at the range level and the
 * multirange parser hands the whole range literal to the range codec untouched.</p>
 *
 * <p>Binary format: an {@code int32} range count, then for each range an {@code int32} byte length
 * followed by that range's {@code range_send} payload — the same payload {@link RangeCodec}
 * produces and consumes.</p>
 */
public final class MultirangeCodec implements StreamingBinaryCodec, TextCodec {

  public static final MultirangeCodec INSTANCE = new MultirangeCodec();

  private MultirangeCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "multirange";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGmultirange.class;
  }

  /**
   * Resolves the OID of the range type this multirange is over. A multirange carries
   * {@code typelem == 0}, so the range type comes from the metadata: the value already cached on the
   * {@link TypeDescriptor} when present, otherwise from {@link CodecContext#resolveType(int)}, which
   * loads {@code pg_range.rngtypid} lazily. Returns {@code 0} when it cannot be resolved.
   */
  private static int resolveRangeOid(TypeDescriptor type, CodecContext ctx) throws SQLException {
    int rangeOid = type.getMultirangeRange();
    if (rangeOid == 0) {
      rangeOid = ctx.resolveType(type.getOid()).getMultirangeRange();
    }
    return rangeOid;
  }

  // ==================== Binary Codec Methods ====================

  @Override
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }

    CodecDepth.enter();
    try {
      int rangeOid = resolveRangeOid(type, ctx);
      if (rangeOid == 0) {
        throw new PSQLException(GT.tr(
            "Cannot decode multirange {0} in binary: its range type (pg_range.rngtypid) "
                + "could not be resolved.", type.getFullName()), PSQLState.DATA_ERROR);
      }
      TypeDescriptor rangeType = ctx.resolveType(rangeOid);
      BinaryCodec rangeCodec = ctx.resolveBinaryCodec(rangeOid);
      if (rangeCodec == null) {
        throw new PSQLException(GT.tr(
            "Cannot decode multirange {0} in binary: no binary codec for range OID {1}.",
            type.getFullName(), rangeOid), PSQLState.DATA_ERROR);
      }

      if (data.length < 4) {
        throw new PSQLException(GT.tr("Invalid multirange binary data: missing range count"),
            PSQLState.DATA_ERROR);
      }
      int count = ByteConverter.int4(data, 0);
      if (count < 0) {
        throw new PSQLException(GT.tr("Invalid multirange binary data: negative range count {0}",
            count), PSQLState.DATA_ERROR);
      }

      int offset = 4;
      List<PGRange<Object>> ranges = new ArrayList<>(Math.min(count, 1024));
      for (int i = 0; i < count; i++) {
        if (offset + 4 > data.length) {
          throw new PSQLException(GT.tr("Invalid multirange binary data: missing range length"),
              PSQLState.DATA_ERROR);
        }
        int len = ByteConverter.int4(data, offset);
        offset += 4;
        if (len < 0 || offset + len > data.length) {
          throw new PSQLException(GT.tr("Invalid multirange binary data: range truncated"),
              PSQLState.DATA_ERROR);
        }
        // Slice-decode each range in place: range_send is exactly what RangeCodec reads.
        PGRange<Object> range = asRange(rangeCodec.decodeBinary(data, offset, len, rangeType, ctx));
        offset += len;
        // The server drops empty ranges from a multirange; mirror the text path for symmetry.
        if (!range.isEmpty()) {
          ranges.add(range);
        }
      }
      return newMultirange(ranges, type);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    BackpatchByteArrayOutputStream out = new BackpatchByteArrayOutputStream();
    try {
      encodeBinary(value, type, ctx, out);
    } catch (IOException e) {
      throw new AssertionError(e); // BackpatchByteArrayOutputStream never throws
    }
    return out.toByteArray();
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    if (!(value instanceof PGmultirange)) {
      throw new PSQLException(GT.tr("Cannot encode {0} as multirange type", value.getClass().getName()),
          PSQLState.DATA_TYPE_MISMATCH);
    }

    CodecDepth.enter();
    try {
      PGmultirange<?> multirange = (PGmultirange<?>) value;

      int rangeOid = resolveRangeOid(type, ctx);
      if (rangeOid == 0) {
        throw new PSQLException(GT.tr(
            "Cannot encode multirange {0} in binary: its range type (pg_range.rngtypid) "
                + "could not be resolved.", type.getFullName()), PSQLState.DATA_ERROR);
      }
      TypeDescriptor rangeType = ctx.resolveType(rangeOid);
      BinaryCodec rangeCodec = ctx.resolveBinaryCodec(rangeOid);
      if (rangeCodec == null) {
        throw new PSQLException(GT.tr(
            "Cannot encode multirange {0} in binary: no binary codec for range OID {1}.",
            type.getFullName(), rangeOid), PSQLState.DATA_ERROR);
      }

      List<? extends PGRange<?>> ranges = multirange.getRanges();
      out.writeInt32(ranges.size());
      for (PGRange<?> range : ranges) {
        if (rangeCodec instanceof StreamingBinaryCodec) {
          int lengthSlot = out.reserveInt32();
          int startPos = out.position();
          ((StreamingBinaryCodec) rangeCodec).encodeBinary(range, rangeType, ctx, out);
          out.setInt32At(lengthSlot, out.position() - startPos);
        } else {
          byte[] payload = rangeCodec.encodeBinary(range, rangeType, ctx);
          out.writeInt32(payload.length);
          out.write(payload, 0, payload.length);
        }
      }
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public int decodeAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert multirange to int"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public long decodeAsLong(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert multirange to long"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public double decodeAsDouble(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert multirange to double"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert multirange to BigDecimal"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    Object multirange = decodeBinary(data, type, ctx);
    return multirange != null ? multirange.toString() : null;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == PGmultirange.class || targetClass == Object.class) {
      return (T) decodeBinary(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) decodeAsString(data, type, ctx);
    }
    throw new PSQLException(
        GT.tr("Cannot decode multirange to {0}", targetClass.getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  // ==================== Text Codec Methods ====================

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data == null || data.isEmpty()) {
      return null;
    }
    return decodeMultirange(LiteralCursor.over(data), type, ctx);
  }

  @Override
  public @Nullable Object decodeText(char[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length == 0) {
      return null;
    }
    // Slice form: parse a multirange nested in a composite/array directly off the
    // borrowed char[] without materializing a per-element String first.
    return decodeMultirange(new LiteralCursor(data, offset, length), type, ctx);
  }

  /**
   * Parses a multirange literal off {@code cur}: a brace-delimited, comma-separated list of range
   * literals. Each range is peeled off the same cursor by {@link RangeCodec#decodeRange}, so a
   * comma-containing quoted bound stays inside its range rather than splitting the list.
   */
  private static @Nullable Object decodeMultirange(LiteralCursor cur, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    int rangeOid = resolveRangeOid(type, ctx);
    if (rangeOid == 0) {
      throw new PSQLException(GT.tr(
          "Cannot decode multirange {0}: its range type (pg_range.rngtypid) could not be resolved.",
          type.getFullName()), PSQLState.DATA_ERROR);
    }
    TypeDescriptor rangeType = ctx.resolveType(rangeOid);

    CodecDepth.enter();
    try {
      cur.expect('{');
      List<PGRange<Object>> ranges = new ArrayList<>();
      // tryConsume('}') handles the empty multirange {} and the closing brace after the last range.
      if (!cur.tryConsume('}')) {
        do {
          PGRange<Object> range = asRange(RangeCodec.decodeRange(cur, rangeType, ctx));
          // The server drops empty ranges from a multirange ({empty} -> {}, {[1,2),empty} ->
          // {[1,2)}); match that so a hand-built literal decodes the way the server would.
          if (!range.isEmpty()) {
            ranges.add(range);
          }
        } while (cur.tryConsume(','));
        cur.expect('}');
      }
      return newMultirange(ranges, type);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof PGmultirange) {
      return value.toString();
    }
    throw new PSQLException(GT.tr("Cannot encode {0} as multirange type", value.getClass().getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == PGmultirange.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    throw new PSQLException(
        GT.tr("Cannot decode multirange to {0}", targetClass.getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert multirange to int"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public long decodeAsLong(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert multirange to long"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public double decodeAsDouble(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert multirange to double"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert multirange to BigDecimal"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return data;
  }

  // ==================== Helpers ====================

  /** Wraps the decoded ranges in a {@link PGmultirange} labelled with the multirange's type name. */
  private static PGmultirange<Object> newMultirange(List<PGRange<Object>> ranges, TypeDescriptor type) {
    PGmultirange<Object> multirange = new PGmultirange<>(ranges);
    multirange.setType(type.getFullName());
    return multirange;
  }

  /** Casts a decoded range, failing clearly if the range codec produced something else. */
  @SuppressWarnings("unchecked")
  private static PGRange<Object> asRange(@Nullable Object decoded) throws SQLException {
    if (decoded instanceof PGRange) {
      return (PGRange<Object>) decoded;
    }
    throw new PSQLException(
        GT.tr("Multirange element did not decode to a range: {0}",
            decoded == null ? "null" : decoded.getClass().getName()),
        PSQLState.DATA_ERROR);
  }
}
