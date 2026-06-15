/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.BooleanTypeUtil;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL bool type.
 *
 * <p>Based on values accepted by PostgreSQL server:
 * https://www.postgresql.org/docs/current/static/datatype-boolean.html</p>
 */
public final class BoolCodec implements BinaryCodec, TextCodec, ArrayElementCodec {

  public static final BoolCodec INSTANCE = new BoolCodec();

  private BoolCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "bool";
  }

  @Override
  public boolean mayRequireQuoting() {
    // Output is t or f — never needs composite/array quoting.
    return false;
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Boolean.class;
  }

  @Override
  public ArrayLeafCodec arrayLeaf() {
    return BoolArrayLeafCodec.INSTANCE;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsBoolean(data, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, PgType type,
      CodecContext ctx) throws SQLException {
    if (length != 1) {
      throw new PSQLException(
          GT.tr("Invalid bool binary data length: {0}", length),
          PSQLState.DATA_ERROR);
    }
    return data[offset] == 1;
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    boolean b = toBoolean(value);
    return new byte[]{(byte) (b ? 1 : 0)};
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsBoolean(data, type, ctx);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    boolean b = toBoolean(value);
    return b ? "t" : "f";
  }

  @Override
  public boolean decodeAsBoolean(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data.length != 1) {
      throw new PSQLException(
          GT.tr("Invalid bool binary data length: {0}", data.length),
          PSQLState.DATA_ERROR);
    }
    return data[0] == 1;
  }

  @Override
  public boolean decodeAsBoolean(String data, PgType type, CodecContext ctx) throws SQLException {
    return BooleanTypeUtil.fromString(data);
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    requireBooleanToNumeric(ctx, "int");
    return decodeAsBoolean(data, type, ctx) ? 1 : 0;
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    requireBooleanToNumeric(ctx, "int");
    return decodeAsBoolean(data, type, ctx) ? 1 : 0;
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    requireBooleanToNumeric(ctx, "long");
    return decodeAsBoolean(data, type, ctx) ? 1L : 0L;
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    requireBooleanToNumeric(ctx, "long");
    return decodeAsBoolean(data, type, ctx) ? 1L : 0L;
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    requireBooleanToNumeric(ctx, "double");
    return decodeAsBoolean(data, type, ctx) ? 1.0 : 0.0;
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    requireBooleanToNumeric(ctx, "double");
    return decodeAsBoolean(data, type, ctx) ? 1.0 : 0.0;
  }

  @Override
  public float decodeAsFloat(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    requireBooleanToNumeric(ctx, "float");
    return decodeAsBoolean(data, type, ctx) ? 1.0f : 0.0f;
  }

  @Override
  public float decodeAsFloat(String data, PgType type, CodecContext ctx) throws SQLException {
    requireBooleanToNumeric(ctx, "float");
    return decodeAsBoolean(data, type, ctx) ? 1.0f : 0.0f;
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    requireBooleanToNumeric(ctx, "BigDecimal");
    return decodeAsBoolean(data, type, ctx) ? BigDecimal.ONE : BigDecimal.ZERO;
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(String data, PgType type, CodecContext ctx) throws SQLException {
    requireBooleanToNumeric(ctx, "BigDecimal");
    return decodeAsBoolean(data, type, ctx) ? BigDecimal.ONE : BigDecimal.ZERO;
  }

  /**
   * Throws if the {@code convertBooleanToNumeric} connection property is disabled.
   *
   * <p>Without the property, numeric getters on a BOOL column are an unsupported
   * conversion (matches the historical PgResultSet behavior).</p>
   */
  private static void requireBooleanToNumeric(CodecContext ctx, String targetType)
      throws PSQLException {
    if (!ctx.getConvertBooleanToNumeric()) {
      throw new PSQLException(
          GT.tr("Cannot convert the column of type {0} to requested type {1}.",
              "bool", targetType),
          PSQLState.DATA_TYPE_MISMATCH);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    boolean value = decodeAsBoolean(data, type, ctx);
    if (targetClass == Boolean.class || targetClass == Object.class) {
      return (T) Boolean.valueOf(value);
    }
    if (targetClass == Integer.class) {
      return (T) Integer.valueOf(value ? 1 : 0);
    }
    if (targetClass == Long.class) {
      return (T) Long.valueOf(value ? 1L : 0L);
    }
    if (targetClass == Short.class) {
      return (T) Short.valueOf((short) (value ? 1 : 0));
    }
    if (targetClass == Byte.class) {
      return (T) Byte.valueOf((byte) (value ? 1 : 0));
    }
    if (targetClass == Double.class) {
      return (T) Double.valueOf(value ? 1.0 : 0.0);
    }
    if (targetClass == Float.class) {
      return (T) Float.valueOf(value ? 1.0f : 0.0f);
    }
    if (targetClass == BigDecimal.class) {
      return (T) (value ? BigDecimal.ONE : BigDecimal.ZERO);
    }
    if (targetClass == String.class) {
      return (T) String.valueOf(value);
    }
    throw Codec.cannotDecode("bool", targetClass.getName());
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    boolean value = decodeAsBoolean(data, type, ctx);
    byte[] bytes = new byte[]{(byte) (value ? 1 : 0)};
    return decodeBinaryAs(bytes, type, targetClass, ctx);
  }

  static boolean toBoolean(Object value) throws SQLException {
    return BooleanTypeUtil.castToBoolean(value);
  }
}
