/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PGmoney;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Codec for the PostgreSQL {@code money} type.
 *
 * <p>The scalar value keeps its existing behaviour by delegating to {@link FallbackCodec} (the map of
 * {@code money} to {@link org.postgresql.util.PGmoney} lives in the JDBC type registry, not here).
 * The only thing this codec adds is a typed array leaf so {@code money[]} decodes to {@code Double[]}
 * through the array codec walker rather than the legacy {@code ArrayDecoding} path — see
 * {@link MoneyArrayLeafCodec}.</p>
 */
public final class MoneyCodec implements BinaryCodec, TextCodec, ArrayElementCodec {

  public static final MoneyCodec INSTANCE = new MoneyCodec();

  private static final FallbackCodec SCALAR = FallbackCodec.INSTANCE;

  private MoneyCodec() {
  }

  @Override
  public String getTypeName() {
    return "money";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return SCALAR.getDefaultJavaType();
  }

  @Override
  public ArrayLeafCodec arrayLeaf() {
    return MoneyArrayLeafCodec.INSTANCE;
  }

  // ----------------------------- scalar: delegate to the fallback codec -----------------------------

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return SCALAR.decodeBinary(data, type, ctx);
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    return SCALAR.decodeText(data, type, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    return SCALAR.encodeBinary(value, type, ctx);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    return SCALAR.encodeText(value, type, ctx);
  }

  @Override
  public boolean canEncodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    return SCALAR.canEncodeBinary(value, type, ctx);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return SCALAR.decodeAsString(data, type, ctx);
  }

  @Override
  public @Nullable String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    return SCALAR.decodeAsString(data, type, ctx);
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (isMoneyTarget(targetClass)) {
      String text = SCALAR.decodeAsString(data, type, ctx);
      return text == null ? null : targetClass.cast(new PGmoney(text));
    }
    return SCALAR.decodeBinaryAs(data, type, targetClass, ctx);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (isMoneyTarget(targetClass)) {
      return targetClass.cast(new PGmoney(data));
    }
    return SCALAR.decodeTextAs(data, type, targetClass, ctx);
  }

  /**
   * Whether the {@code getObject} target asks for the {@code money} object form. The JDBC type
   * registry maps {@code money} to {@link PGmoney}, so a plain {@code getObject} (and a {@code money[]}
   * element read via {@code getResultSet()}) resolves to {@link PGmoney}; the fallback codec only
   * builds a bare {@link PGobject}, so this codec instantiates {@link PGmoney} itself.
   */
  private static boolean isMoneyTarget(Class<?> targetClass) {
    return targetClass == PGmoney.class || targetClass == PGobject.class || targetClass == Object.class;
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return SCALAR.decodeAsInt(data, type, ctx);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    return SCALAR.decodeAsInt(data, type, ctx);
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return SCALAR.decodeAsLong(data, type, ctx);
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    return SCALAR.decodeAsLong(data, type, ctx);
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return SCALAR.decodeAsDouble(data, type, ctx);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    return SCALAR.decodeAsDouble(data, type, ctx);
  }
}
