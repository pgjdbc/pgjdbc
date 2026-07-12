/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.CodecDepth;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGRange;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Codec for PostgreSQL range types.
 *
 * <p>This codec handles encoding and decoding of PostgreSQL range types such as
 * int4range, int8range, numrange, tsrange, tstzrange, and daterange.</p>
 *
 * <p>Range types have a text format like: [lower,upper), (lower,upper], [,upper), etc.
 * The first character is '[' (inclusive) or '(' (exclusive) for the lower bound,
 * followed by the lower value (or empty for unbounded), a comma, the upper value
 * (or empty for unbounded), and finally ']' (inclusive) or ')' (exclusive).</p>
 *
 * <p>Binary format consists of:</p>
 * <ul>
 *   <li>1 byte flags: empty (0x01), lower inclusive (0x02), upper inclusive (0x04),
 *       lower infinite (0x08), upper infinite (0x10)</li>
 *   <li>If lower bound exists: 4-byte length + bound data</li>
 *   <li>If upper bound exists: 4-byte length + bound data</li>
 * </ul>
 */
public final class RangeCodec implements StreamingBinaryCodec, TextCodec {

  public static final RangeCodec INSTANCE = new RangeCodec();

  // Binary format flags
  private static final byte FLAG_EMPTY = 0x01;
  private static final byte FLAG_LOWER_INCLUSIVE = 0x02;
  private static final byte FLAG_UPPER_INCLUSIVE = 0x04;
  private static final byte FLAG_LOWER_INFINITE = 0x08;
  private static final byte FLAG_UPPER_INFINITE = 0x10;

  private RangeCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "range";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGRange.class;
  }

  // ==================== Binary Codec Methods ====================

  @Override
  public @Nullable Object decodeBinary(byte[] buf, int start, int len, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (len == 0) {
      return null;
    }
    // Range binary parsing indexes from the value start; copy only for a genuine sub-slice.
    byte[] data = start == 0 && len == buf.length ? buf : Arrays.copyOfRange(buf, start, start + len);

    CodecDepth.enter();
    try {
      byte flags = data[0];

      // Check for empty range
      if ((flags & FLAG_EMPTY) != 0) {
        PGRange<Object> range = PGRange.empty();
        range.setType(type.getFullName());
        return range;
      }

      boolean lowerInclusive = (flags & FLAG_LOWER_INCLUSIVE) != 0;
      boolean upperInclusive = (flags & FLAG_UPPER_INCLUSIVE) != 0;
      boolean lowerInfinite = (flags & FLAG_LOWER_INFINITE) != 0;
      boolean upperInfinite = (flags & FLAG_UPPER_INFINITE) != 0;

      // Resolve the subtype codec. pg_type.typelem is 0 for ranges; the real subtype
      // lives in pg_range.rngsubtype (loaded lazily via TypeInfo).
      int subtypeOid = resolveSubtypeOid(type, ctx);
      if (subtypeOid == 0) {
        throw Exceptions.rangeSubtypeUnresolvedForDecode(type.getFullName());
      }
      TypeDescriptor subtypeType = ctx.resolveType(subtypeOid);
      BinaryCodec subtypeCodec = ctx.resolveBinaryCodec(subtypeOid);
      if (subtypeCodec == null) {
        throw Exceptions.rangeSubtypeCodecMissingForDecode(type.getFullName(), subtypeOid);
      }

      int offset = 1;
      Object lower = null;
      Object upper = null;

      // Read lower bound if not infinite
      if (!lowerInfinite) {
        if (offset + 4 > data.length) {
          throw Exceptions.invalidRangeMissingLowerBoundLength();
        }
        int lowerLen = ByteConverter.int4(data, offset);
        offset += 4;
        if (lowerLen >= 0) {
          if (offset + lowerLen > data.length) {
            throw Exceptions.invalidRangeLowerBoundTruncated();
          }
          lower = subtypeCodec.decodeBinary(data, offset, lowerLen, subtypeType, ctx);
          offset += lowerLen;
        }
      }

      // Read upper bound if not infinite
      if (!upperInfinite) {
        if (offset + 4 > data.length) {
          throw Exceptions.invalidRangeMissingUpperBoundLength();
        }
        int upperLen = ByteConverter.int4(data, offset);
        offset += 4;
        if (upperLen >= 0) {
          if (offset + upperLen > data.length) {
            throw Exceptions.invalidRangeUpperBoundTruncated();
          }
          upper = subtypeCodec.decodeBinary(data, offset, upperLen, subtypeType, ctx);
        }
      }

      PGRange<Object> range = new PGRange<>(lower, upper, lowerInclusive, upperInclusive);
      range.setType(type.getFullName());
      return range;
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
      // BackpatchByteArrayOutputStream never throws; keep the historical error mapping regardless.
      throw Exceptions.errorEncodingRange(e);
    }
    return out.toByteArray();
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    if (!(value instanceof PGRange)) {
      throw Exceptions.cannotEncodeRange(value);
    }

    CodecDepth.enter();
    try {
      @SuppressWarnings("unchecked")
      PGRange<Object> range = (PGRange<Object>) value;

      if (range.isEmpty()) {
        out.writeByte(FLAG_EMPTY);
        return;
      }

      // Resolve the subtype codec. pg_type.typelem is 0 for ranges; the real subtype
      // lives in pg_range.rngsubtype (loaded lazily via TypeInfo).
      int subtypeOid = resolveSubtypeOid(type, ctx);
      if (subtypeOid == 0) {
        throw Exceptions.rangeSubtypeUnresolvedForEncode(type.getFullName());
      }
      TypeDescriptor subtypeType = ctx.resolveType(subtypeOid);
      BinaryCodec subtypeCodec = ctx.resolveBinaryCodec(subtypeOid);
      if (subtypeCodec == null) {
        throw Exceptions.rangeSubtypeCodecMissingForEncode(type.getFullName(), subtypeOid);
      }

      // Calculate flags
      byte flags = 0;
      if (range.isLowerInclusive()) {
        flags |= FLAG_LOWER_INCLUSIVE;
      }
      if (range.isUpperInclusive()) {
        flags |= FLAG_UPPER_INCLUSIVE;
      }
      if (!range.hasLowerBound()) {
        flags |= FLAG_LOWER_INFINITE;
      }
      if (!range.hasUpperBound()) {
        flags |= FLAG_UPPER_INFINITE;
      }
      out.writeByte(flags);

      // Write lower bound if not infinite
      if (range.hasLowerBound()) {
        Object bound = castNonNull(range.getLower());
        BinaryCodec.writeElement(out, bound, subtypeCodec, subtypeType, ctx);
      }

      // Write upper bound if not infinite
      if (range.hasUpperBound()) {
        Object bound = castNonNull(range.getUpper());
        BinaryCodec.writeElement(out, bound, subtypeCodec, subtypeType, ctx);
      }
    } finally {
      CodecDepth.exit();
    }
  }

  /**
   * Resolves the range's subtype OID from {@code pg_range.rngsubtype}. A range carries
   * {@code typelem == 0}, so the subtype is taken from the type metadata: the value already cached
   * on {@link TypeDescriptor} when present, otherwise from {@link CodecContext#resolveType(int)},
   * which loads it lazily. Returns {@code 0} when no context is available (the codec unit tests pass
   * a {@code null} context) or the subtype cannot be resolved, in which case the bounds stay as raw
   * strings.
   */
  private static int resolveSubtypeOid(TypeDescriptor type, @Nullable CodecContext ctx) throws SQLException {
    int subtypeOid = type.getRangeSubtype();
    if (subtypeOid == 0 && ctx != null) {
      subtypeOid = ctx.resolveType(type.getOid()).getRangeSubtype();
    }
    return subtypeOid;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass == PGRange.class || targetClass == Object.class) {
      return (T) decodeBinary(data, offset, length, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) decodeAsString(data, offset, length, type, ctx);
    }
    throw Exceptions.cannotDecodeRangeTo(targetClass.getName());
  }

  // ==================== Text Codec Methods ====================

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data == null || data.isEmpty()) {
      return null;
    }
    return decodeRange(LiteralCursor.over(data), type, ctx);
  }

  @Override
  public @Nullable Object decodeText(char[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length == 0) {
      return null;
    }
    // Slice form: parse a range nested in a composite/array directly off the
    // borrowed char[] without materializing a per-element String first.
    return decodeRange(new LiteralCursor(data, offset, length), type, ctx);
  }

  /**
   * Parses one range literal off {@code cur}, driving the shared {@link LiteralCursor}
   * so the same code serves the String and slice forms. On return the cursor sits just
   * past the range's closing bracket, so {@link MultirangeCodec} can call this in a loop
   * to peel the ranges out of a {@code {...}} multirange literal off the same cursor.
   *
   * @param cur the cursor positioned at the start of a range literal
   * @param type the range type, used to resolve the bound subtype and label the result
   * @param ctx the codec context, or {@code null} to keep bounds as raw strings
   */
  static @Nullable Object decodeRange(LiteralCursor cur, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    cur.skipWhitespace();
    if (cur.consumeKeyword("empty")) {
      PGRange<Object> range = PGRange.empty();
      range.setType(type.getFullName());
      return range;
    }

    CodecDepth.enter();
    try {
      // pg_type.typelem is 0 for ranges; the real subtype lives in pg_range.rngsubtype
      // (loaded lazily via TypeInfo). With a connection-bound context the bounds are
      // decoded by the subtype's text codec into typed values; without one (the codec
      // unit tests, which pass a null context) the subtype stays unresolved and the
      // bounds are kept as their raw strings.
      int subtypeOid = resolveSubtypeOid(type, ctx);
      TypeDescriptor subtypeType = subtypeOid != 0 && ctx != null ? ctx.resolveType(subtypeOid) : null;
      Codec subtypeCodec = subtypeOid != 0 && ctx != null ? ctx.resolveCodec(subtypeOid) : null;
      TextCodec boundCodec =
          subtypeCodec instanceof TextCodec && subtypeType != null ? (TextCodec) subtypeCodec : null;

      char open = cur.peek();
      boolean lowerInclusive;
      if (open == '[') {
        lowerInclusive = true;
      } else if (open == '(') {
        lowerInclusive = false;
      } else {
        throw Exceptions.invalidRangeBoundaryFormat("'[' or '('", cur.literal());
      }
      cur.expect(open);

      // Lower bound, terminated by the ',' separator.
      cur.readValue(',', ']', ')');
      Object lower = decodeBound(cur, boundCodec, subtypeType, ctx);
      cur.expect(',');

      // Upper bound, terminated by the ']' or ')' closing bracket.
      cur.readValue(',', ']', ')');
      Object upper = decodeBound(cur, boundCodec, subtypeType, ctx);

      char close = cur.peek();
      boolean upperInclusive;
      if (close == ']') {
        upperInclusive = true;
      } else if (close == ')') {
        upperInclusive = false;
      } else {
        throw Exceptions.invalidRangeBoundaryFormat("']' or ')'", cur.literal());
      }
      cur.expect(close);

      PGRange<Object> range = new PGRange<>(lower, upper, lowerInclusive, upperInclusive);
      range.setType(type.getFullName());
      return range;
    } finally {
      CodecDepth.exit();
    }
  }

  /**
   * Decodes the cursor's current token as a range bound. An unquoted empty token
   * is an infinite/unbounded bound ({@code null}); otherwise the bound slice is
   * decoded by the subtype text codec when known, or kept as its raw string.
   */
  private static @Nullable Object decodeBound(LiteralCursor cur, @Nullable TextCodec boundCodec,
      @Nullable TypeDescriptor subtypeType, CodecContext ctx) throws SQLException {
    if (!cur.tokenWasQuoted() && cur.tokenLength() == 0) {
      return null; // infinite / unbounded
    }
    if (boundCodec != null && subtypeType != null) {
      Object decoded = boundCodec.decodeText(cur.tokenChars(), cur.tokenOffset(),
          cur.tokenLength(), subtypeType, ctx);
      if (decoded != null) {
        return decoded;
      }
    }
    return new String(cur.tokenChars(), cur.tokenOffset(), cur.tokenLength());
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof PGRange) {
      return value.toString();
    }
    throw Exceptions.cannotEncodeRange(value);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == PGRange.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    throw Exceptions.cannotDecodeRangeTo(targetClass.getName());
  }

  @Override
  public @Nullable String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return data;
  }
}
