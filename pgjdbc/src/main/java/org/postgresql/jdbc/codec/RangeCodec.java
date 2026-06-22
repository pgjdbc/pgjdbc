/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.CodecDepth;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PGRange;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;

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
public final class RangeCodec implements BinaryCodec, TextCodec {

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
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }

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

      // Get element type codec
      int elementOid = type.getTypelem();
      PgType elementType = ctx.getTypeInfo().getPgTypeByOid(elementOid);
      BinaryCodec elementCodec = ctx.getCodecs().getBinaryCodec(elementOid);

      int offset = 1;
      Object lower = null;
      Object upper = null;

      // Read lower bound if not infinite
      if (!lowerInfinite) {
        if (offset + 4 > data.length) {
          throw new PSQLException(GT.tr("Invalid range binary data: missing lower bound length"),
              PSQLState.DATA_ERROR);
        }
        int lowerLen = ByteConverter.int4(data, offset);
        offset += 4;
        if (lowerLen >= 0) {
          if (offset + lowerLen > data.length) {
            throw new PSQLException(GT.tr("Invalid range binary data: lower bound truncated"),
                PSQLState.DATA_ERROR);
          }
          lower = elementCodec.decodeBinary(data, offset, lowerLen, elementType, ctx);
          offset += lowerLen;
        }
      }

      // Read upper bound if not infinite
      if (!upperInfinite) {
        if (offset + 4 > data.length) {
          throw new PSQLException(GT.tr("Invalid range binary data: missing upper bound length"),
              PSQLState.DATA_ERROR);
        }
        int upperLen = ByteConverter.int4(data, offset);
        offset += 4;
        if (upperLen >= 0) {
          if (offset + upperLen > data.length) {
            throw new PSQLException(GT.tr("Invalid range binary data: upper bound truncated"),
                PSQLState.DATA_ERROR);
          }
          upper = elementCodec.decodeBinary(data, offset, upperLen, elementType, ctx);
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
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (!(value instanceof PGRange)) {
      throw new PSQLException(GT.tr("Cannot encode {0} as range type", value.getClass().getName()),
          PSQLState.DATA_TYPE_MISMATCH);
    }

    CodecDepth.enter();
    try {
      @SuppressWarnings("unchecked")
      PGRange<Object> range = (PGRange<Object>) value;

      if (range.isEmpty()) {
        return new byte[]{FLAG_EMPTY};
      }

      // Get element type codec
      int elementOid = type.getTypelem();
      PgType elementType = ctx.getTypeInfo().getPgTypeByOid(elementOid);
      BinaryCodec elementCodec = ctx.getCodecs().getBinaryCodec(elementOid);

      ByteArrayOutputStream out = new ByteArrayOutputStream();

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
      out.write(flags);

      // Write lower bound if not infinite
      if (range.hasLowerBound()) {
        byte[] lowerData = elementCodec.encodeBinary(castNonNull(range.getLower()), elementType, ctx);
        writeLengthPrefixed(out, lowerData);
      }

      // Write upper bound if not infinite
      if (range.hasUpperBound()) {
        byte[] upperData = elementCodec.encodeBinary(castNonNull(range.getUpper()), elementType, ctx);
        writeLengthPrefixed(out, upperData);
      }

      return out.toByteArray();
    } catch (IOException e) {
      throw new PSQLException(GT.tr("Error encoding range"), PSQLState.DATA_ERROR, e);
    } finally {
      CodecDepth.exit();
    }
  }

  private static void writeLengthPrefixed(ByteArrayOutputStream out, byte[] data) throws IOException {
    byte[] lenBytes = new byte[4];
    ByteConverter.int4(lenBytes, 0, data.length);
    out.write(lenBytes);
    out.write(data);
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert range to int"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert range to long"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert range to double"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert range to BigDecimal"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Object range = decodeBinary(data, type, ctx);
    return range != null ? range.toString() : null;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == PGRange.class || targetClass == Object.class) {
      return (T) decodeBinary(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) decodeAsString(data, type, ctx);
    }
    throw new PSQLException(
        GT.tr("Cannot decode range to {0}", targetClass.getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  // ==================== Text Codec Methods ====================

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    if (data == null || data.isEmpty()) {
      return null;
    }
    return decodeRange(LiteralCursor.over(data), type, ctx);
  }

  @Override
  public @Nullable Object decodeText(char[] data, int offset, int length, PgType type,
      CodecContext ctx) throws SQLException {
    if (length == 0) {
      return null;
    }
    // Slice form: parse a range nested in a composite/array directly off the
    // borrowed char[] without materializing a per-element String first.
    return decodeRange(new LiteralCursor(data, offset, length), type, ctx);
  }

  /**
   * Parses a range literal off {@code cur}, driving the shared {@link LiteralCursor}
   * so the same code serves the String and slice forms.
   */
  private static @Nullable Object decodeRange(LiteralCursor cur, PgType type, CodecContext ctx)
      throws SQLException {
    cur.skipWhitespace();
    if (cur.consumeKeyword("empty")) {
      PGRange<Object> range = PGRange.empty();
      range.setType(type.getFullName());
      return range;
    }

    CodecDepth.enter();
    try {
      // pg_type.typelem is zero for range types — the subtype lives in pg_range.
      // We don't load pg_range yet, so fall back to leaving the bound text values
      // unparsed when the subtype OID is unknown. Element typing for ranges is
      // tracked as a follow-up.
      int elementOid = type.getTypelem();
      PgType elementType = elementOid != 0 ? ctx.getTypeInfo().getPgTypeByOid(elementOid) : null;
      Codec elementCodec = elementOid != 0 ? ctx.getCodecs().getByOid(elementOid, elementType) : null;
      TextCodec boundCodec =
          elementCodec instanceof TextCodec && elementType != null ? (TextCodec) elementCodec : null;

      char open = cur.peek();
      boolean lowerInclusive;
      if (open == '[') {
        lowerInclusive = true;
      } else if (open == '(') {
        lowerInclusive = false;
      } else {
        throw new PSQLException(
            GT.tr("Invalid range format, expected '[' or '(': {0}", cur.literal()),
            PSQLState.DATA_TYPE_MISMATCH);
      }
      cur.expect(open);

      // Lower bound, terminated by the ',' separator.
      cur.readValue(',', ']', ')');
      Object lower = decodeBound(cur, boundCodec, elementType, ctx);
      cur.expect(',');

      // Upper bound, terminated by the ']' or ')' closing bracket.
      cur.readValue(',', ']', ')');
      Object upper = decodeBound(cur, boundCodec, elementType, ctx);

      char close = cur.peek();
      boolean upperInclusive;
      if (close == ']') {
        upperInclusive = true;
      } else if (close == ')') {
        upperInclusive = false;
      } else {
        throw new PSQLException(
            GT.tr("Invalid range format, expected ']' or ')': {0}", cur.literal()),
            PSQLState.DATA_TYPE_MISMATCH);
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
      @Nullable PgType elementType, CodecContext ctx) throws SQLException {
    if (!cur.tokenWasQuoted() && cur.tokenLength() == 0) {
      return null; // infinite / unbounded
    }
    if (boundCodec != null && elementType != null) {
      Object decoded = boundCodec.decodeText(cur.tokenChars(), cur.tokenOffset(),
          cur.tokenLength(), elementType, ctx);
      if (decoded != null) {
        return decoded;
      }
    }
    return new String(cur.tokenChars(), cur.tokenOffset(), cur.tokenLength());
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (value instanceof PGRange) {
      return value.toString();
    }
    throw new PSQLException(GT.tr("Cannot encode {0} as range type", value.getClass().getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == PGRange.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    throw new PSQLException(
        GT.tr("Cannot decode range to {0}", targetClass.getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert range to int"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert range to long"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert range to double"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert range to BigDecimal"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    return data;
  }
}
