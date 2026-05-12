/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.CodecDepth;
import org.postgresql.jdbc.PgType;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL domain types.
 *
 * <p>Domain types in PostgreSQL are custom types based on underlying base types
 * with optional constraints. This codec delegates all encoding/decoding to the
 * base type's codec.</p>
 *
 * <p>Example: CREATE DOMAIN positive_int AS integer CHECK (value > 0).
 * The positive_int domain will use Int4Codec for its encoding/decoding.</p>
 */
public final class DomainCodec implements BinaryCodec, TextCodec {

  public static final DomainCodec INSTANCE = new DomainCodec();

  private DomainCodec() {
    // Singleton
  }

  /**
   * Gets the base type codec for the given domain type.
   */
  private Codec getBaseCodec(PgType domainType, CodecContext ctx) throws SQLException {
    int baseTypeOid = domainType.getTypbasetype();
    if (baseTypeOid == 0) {
      // Not a domain, fall back to default behavior
      return FallbackCodec.INSTANCE;
    }
    PgType baseType = ctx.getTypeInfo().getPgTypeByOid(baseTypeOid);
    return ctx.getCodecs().getByOid(baseTypeOid, baseType);
  }

  /**
   * Gets the base type for the given domain type.
   */
  private PgType getBaseType(PgType domainType, CodecContext ctx) throws SQLException {
    int baseTypeOid = domainType.getTypbasetype();
    if (baseTypeOid == 0) {
      return domainType;
    }
    return ctx.getTypeInfo().getPgTypeByOid(baseTypeOid);
  }

  @Override
  public String getTypeName() {
    return "domain";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    // Domain's default Java type depends on the base type
    return Object.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeBinary(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeBinary(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).encodeBinary(value, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.encodeBinary(value, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeText(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeText(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).encodeText(value, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.encodeText(value, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeBinaryAs(data, baseType, targetClass, ctx);
      }
      return FallbackCodec.INSTANCE.decodeBinaryAs(data, baseType, targetClass, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeTextAs(data, baseType, targetClass, ctx);
      }
      return FallbackCodec.INSTANCE.decodeTextAs(data, baseType, targetClass, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeAsInt(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsInt(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeAsInt(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsInt(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeAsLong(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsLong(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeAsLong(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsLong(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeAsDouble(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsDouble(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeAsDouble(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsDouble(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeAsBigDecimal(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsBigDecimal(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(String data, PgType type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeAsBigDecimal(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsBigDecimal(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeAsString(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsString(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      PgType baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeAsString(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsString(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }
}
