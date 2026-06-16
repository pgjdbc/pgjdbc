/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.jdbc.codec.CompositeCodec;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;

/**
 * Binary format SQLInput implementation.
 *
 * <p>Reads binary-encoded composite data using the codec infrastructure.</p>
 *
 * <p>Codecs are pre-cached at construction time for performance - each field's
 * codec is looked up once rather than on every read operation.</p>
 */
public final class PgSQLInputBinary extends PgSQLInput<byte[]> {

  /**
   * Pre-cached codecs for each field, indexed by field position.
   * This avoids repeated codec registry lookups during read operations.
   */
  private final BinaryCodec[] cachedCodecs;

  /**
   * Pre-cached PgTypes for each field, indexed by field position.
   */
  private final PgType[] cachedTypes;

  /**
   * Creates a new PgSQLInputBinary from raw composite binary data.
   *
   * @param compositeData the raw binary data for the composite type
   * @param type the composite type
   * @param ctx the codec context
   */
  public PgSQLInputBinary(byte[] compositeData, PgType type, CodecContext ctx)
      throws SQLException {
    super(parseCompositeData(compositeData), type, ctx);
    this.cachedCodecs = new BinaryCodec[fields.size()];
    this.cachedTypes = new PgType[fields.size()];
    cacheCodecs();
  }

  /**
   * Creates a new PgSQLInputBinary from pre-parsed attribute values.
   *
   * @param attributeValues the parsed attribute values (null for SQL NULL)
   * @param type the composite type
   * @param ctx the codec context
   */
  @SuppressWarnings("argument")
  public PgSQLInputBinary(byte @Nullable [][] attributeValues, PgType type, CodecContext ctx)
      throws SQLException {
    super(attributeValues, type, ctx);
    this.cachedCodecs = new BinaryCodec[fields.size()];
    this.cachedTypes = new PgType[fields.size()];
    cacheCodecs();
  }

  /**
   * Pre-caches codecs and types for all fields.
   */
  private void cacheCodecs() throws SQLException {
    for (int i = 0; i < fields.size(); i++) {
      PgField field = fields.get(i);
      int oid = field.getTypeOid();
      PgType fieldType = ctx.getTypeInfo().getPgTypeByOid(oid);
      cachedTypes[i] = fieldType;
      cachedCodecs[i] = castNonNull(ctx.getCodecs().getBinaryCodec(oid, fieldType));
    }
  }

  /**
   * Parses binary composite data into individual field values.
   *
   * <p>Binary composite format:
   * <pre>
   * int4 nfields
   * For each field:
   *   int4 oid
   *   int4 len (-1 for null)
   *   byte[len] data (if len >= 0)
   * </pre>
   */
  private static byte[] @Nullable [] parseCompositeData(byte[] data) throws SQLException {
    List<CompositeCodec.DecodedField> decodedFields = CompositeCodec.decodeBinaryFields(data);

    byte[] @Nullable [] result = new byte[decodedFields.size()][];
    for (int i = 0; i < decodedFields.size(); i++) {
      CompositeCodec.DecodedField field = decodedFields.get(i);
      if (!field.isNull()) {
        result[i] = field.getData();
      }
    }
    return result;
  }

  /**
   * Gets the codec for the current field (the one just read by nextValue()).
   * Since fieldIndex is incremented by nextValue(), the current field is at fieldIndex - 1.
   */
  private BinaryCodec getCodec() {
    return cachedCodecs[fieldIndex - 1];
  }

  /**
   * Gets the type for the current field.
   */
  private PgType getCurrentType() {
    return cachedTypes[fieldIndex - 1];
  }

  @Override
  protected int decodeInt(byte[] data, PgType fieldType) throws SQLException {
    // Use cached codec and type for performance (ignore passed fieldType)
    return getCodec().decodeAsInt(data, getCurrentType(), ctx);
  }

  @Override
  protected long decodeLong(byte[] data, PgType fieldType) throws SQLException {
    return getCodec().decodeAsLong(data, getCurrentType(), ctx);
  }

  @Override
  protected double decodeDouble(byte[] data, PgType fieldType) throws SQLException {
    return getCodec().decodeAsDouble(data, getCurrentType(), ctx);
  }

  @Override
  protected float decodeFloat(byte[] data, PgType fieldType) throws SQLException {
    return (float) getCodec().decodeAsDouble(data, getCurrentType(), ctx);
  }

  @Override
  protected boolean decodeBoolean(byte[] data, PgType fieldType) throws SQLException {
    return getCodec().decodeAsBoolean(data, getCurrentType(), ctx);
  }

  @Override
  protected @Nullable String decodeString(byte[] data, PgType fieldType) throws SQLException {
    return getCodec().decodeAsString(data, getCurrentType(), ctx);
  }

  @Override
  protected @Nullable BigDecimal decodeBigDecimal(byte[] data, PgType fieldType)
      throws SQLException {
    return getCodec().decodeAsBigDecimal(data, getCurrentType(), ctx);
  }

  @Override
  protected byte @Nullable [] decodeBytes(byte[] data, PgType fieldType) throws SQLException {
    return getCodec().decodeAsBytes(data, getCurrentType(), ctx);
  }

  @Override
  protected @Nullable Date decodeDate(byte[] data, PgType fieldType) throws SQLException {
    PgType type = getCurrentType();
    Object value = getCodec().decodeBinary(data, type, ctx);
    if (value instanceof Date) {
      return (Date) value;
    }
    if (value != null) {
      return Date.valueOf(value.toString());
    }
    return null;
  }

  @Override
  protected @Nullable Time decodeTime(byte[] data, PgType fieldType) throws SQLException {
    PgType type = getCurrentType();
    Object value = getCodec().decodeBinary(data, type, ctx);
    if (value instanceof Time) {
      return (Time) value;
    }
    if (value != null) {
      return Time.valueOf(value.toString());
    }
    return null;
  }

  @Override
  protected @Nullable Timestamp decodeTimestamp(byte[] data, PgType fieldType)
      throws SQLException {
    PgType type = getCurrentType();
    Object value = getCodec().decodeBinary(data, type, ctx);
    if (value instanceof Timestamp) {
      return (Timestamp) value;
    }
    if (value != null) {
      return Timestamp.valueOf(value.toString());
    }
    return null;
  }

  @Override
  protected @Nullable Object decodeObject(byte[] data, PgType fieldType) throws SQLException {
    // Honor only the explicit JDBC typeMap here. If no explicit mapping is
    // present, return the codec's default Java type so SPI-provided codecs can
    // surface their own Java objects instead of being forced through the
    // legacy PGobject registry.
    PgType currentType = getCurrentType();
    Class<?> mapped = ctx.getTypeMap().get(currentType.getFullName());
    if (mapped == null) {
      mapped = ctx.getTypeMap().get(currentType.getTypeName().getName());
    }
    if (mapped != null) {
      return getCodec().decodeBinaryAs(data, currentType, mapped, ctx);
    }
    return getCodec().decodeBinary(data, currentType, ctx);
  }

  @Override
  @SuppressWarnings("unchecked")
  protected <T> @Nullable T decodeObjectAs(byte[] data, PgType fieldType, Class<T> type)
      throws SQLException {
    return getCodec().decodeBinaryAs(data, getCurrentType(), type, ctx);
  }

  @Override
  protected Array decodeArray(byte[] data, PgType fieldType) throws SQLException {
    return new PgArray(ctx.getConnection(), getCurrentType().getOid(), data);
  }
}
