/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.codec.CompositeCodec;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;

/**
 * Binary format SQLOutput implementation.
 *
 * <p>Encodes values to binary format using the codec infrastructure.</p>
 *
 * <p>Codecs are pre-cached at construction time for performance.</p>
 */
public final class PgSQLOutputBinary extends PgSQLOutput<byte[]> {

  /**
   * Pre-cached codecs for each field.
   */
  private final BinaryCodec[] cachedCodecs;

  /**
   * Pre-cached field types.
   */
  private final TypeDescriptor[] cachedTypes;

  /**
   * Creates a new PgSQLOutputBinary.
   *
   * @param type the composite type
   * @param ctx the codec context
   */
  public PgSQLOutputBinary(PgType type, PgCodecContext ctx) throws SQLException {
    super(type, ctx);
    this.cachedCodecs = new BinaryCodec[fields.size()];
    this.cachedTypes = new TypeDescriptor[fields.size()];
    cacheCodecs();
  }

  private void cacheCodecs() throws SQLException {
    for (int i = 0; i < fields.size(); i++) {
      PgField field = fields.get(i);
      int oid = field.getTypeOid();
      cachedTypes[i] = ctx.resolveType(oid);
      cachedCodecs[i] = castNonNull(ctx.resolveBinaryCodec(oid));
    }
  }

  private BinaryCodec getCodec() {
    return cachedCodecs[fieldIndex - 1];
  }

  private TypeDescriptor getCurrentType() {
    return cachedTypes[fieldIndex - 1];
  }

  @Override
  protected byte[] encodeInt(int value, PgType fieldType) throws SQLException {
    return getCodec().encodeBinary(value, getCurrentType(), ctx);
  }

  @Override
  protected byte[] encodeLong(long value, PgType fieldType) throws SQLException {
    return getCodec().encodeBinary(value, getCurrentType(), ctx);
  }

  @Override
  protected byte[] encodeDouble(double value, PgType fieldType) throws SQLException {
    return getCodec().encodeBinary(value, getCurrentType(), ctx);
  }

  @Override
  protected byte[] encodeFloat(float value, PgType fieldType) throws SQLException {
    return getCodec().encodeBinary(value, getCurrentType(), ctx);
  }

  @Override
  protected byte[] encodeBoolean(boolean value, PgType fieldType) throws SQLException {
    return getCodec().encodeBinary(value, getCurrentType(), ctx);
  }

  @Override
  protected byte[] encodeString(String value, PgType fieldType) throws SQLException {
    return getCodec().encodeBinary(value, getCurrentType(), ctx);
  }

  @Override
  protected byte[] encodeBigDecimal(BigDecimal value, PgType fieldType) throws SQLException {
    return getCodec().encodeBinary(value, getCurrentType(), ctx);
  }

  @Override
  protected byte[] encodeBytes(byte[] value, PgType fieldType) throws SQLException {
    return getCodec().encodeBinary(value, getCurrentType(), ctx);
  }

  @Override
  protected byte[] encodeDate(Date value, PgType fieldType) throws SQLException {
    return getCodec().encodeBinary(value, getCurrentType(), ctx);
  }

  @Override
  protected byte[] encodeTime(Time value, PgType fieldType) throws SQLException {
    return getCodec().encodeBinary(value, getCurrentType(), ctx);
  }

  @Override
  protected byte[] encodeTimestamp(Timestamp value, PgType fieldType) throws SQLException {
    return getCodec().encodeBinary(value, getCurrentType(), ctx);
  }

  @Override
  protected byte[] encodeObject(Object value, PgType fieldType) throws SQLException {
    return getCodec().encodeBinary(value, getCurrentType(), ctx);
  }

  /**
   * Serializes the collected values to PostgreSQL binary composite format.
   *
   * @return the binary data
   */
  public byte[] toBytes() throws SQLException {
    List<byte[]> values = (List<byte[]>) (List<?>) getAttributeValues();
    byte[][] fieldData = values.toArray(new byte[values.size()][]);
    return CompositeCodec.encodeBinaryFields(fields, fieldData);
  }
}
