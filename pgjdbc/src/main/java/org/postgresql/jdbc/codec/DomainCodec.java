/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.CodecDepth;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL domain types.
 *
 * <p>Domain types in PostgreSQL are custom types based on an underlying base type
 * with optional constraints. This codec delegates all encoding and decoding to the
 * base type's codec.</p>
 *
 * <p>Example: {@code CREATE DOMAIN positive_int AS integer CHECK (value > 0)}.
 * The {@code positive_int} domain uses {@code Int4Codec} for its encoding and decoding.</p>
 *
 * <h2>Contract: a domain is unwrapped transparently to its base type</h2>
 *
 * <p>This codec resolves the domain to its base type ({@code pg_type.typbasetype}) and forwards
 * the wire bytes to the base type's codec, passing the <em>base</em> {@link TypeDescriptor}. Two
 * consequences follow, and both are intentional:</p>
 *
 * <ul>
 *   <li><strong>The base codec sees the base type, not the domain.</strong> A caller that decodes
 *   a domain value receives the Java type of the base type — a {@code positive_int} value comes
 *   back as an {@link Integer}, exactly as a plain {@code integer} would. The DISTINCT identity of
 *   the domain is not reflected in the decoded Java object; it is visible only through metadata
 *   ({@code ResultSetMetaData.getColumnTypeName}, {@code DatabaseMetaData}), which report the
 *   domain rather than its base type.</li>
 *
 *   <li><strong>The domain's type modifier is not propagated.</strong> A domain may pin a typmod
 *   on its base type (for example {@code CREATE DOMAIN price AS numeric(10,2)}), stored in
 *   {@code pg_type.typtypmod}. The base codec is handed the base {@link TypeDescriptor}, so it does
 *   not observe that typmod. This is currently harmless: the numeric codecs encode from the value's
 *   own scale and precision and do not apply a typmod on encode, and the server enforces the
 *   domain constraint on input regardless. Code that needs the domain typmod — column-size
 *   reporting, for instance — must read it from the domain {@link TypeDescriptor} via
 *   {@link TypeDescriptor#getTyptypmod()}, not from anything this codec forwards.</li>
 * </ul>
 */
public final class DomainCodec implements BinaryCodec, TextCodec {

  public static final DomainCodec INSTANCE = new DomainCodec();

  private DomainCodec() {
    // Singleton
  }

  /**
   * Gets the base type codec for the given domain type.
   */
  private static Codec getBaseCodec(TypeDescriptor domainType, CodecContext ctx) throws SQLException {
    int baseTypeOid = domainType.getTypbasetype();
    if (baseTypeOid == 0) {
      // Not a domain, fall back to default behavior
      return FallbackCodec.INSTANCE;
    }
    TypeDescriptor baseType = ctx.getTypeInfo().getPgTypeByOid(baseTypeOid);
    return ctx.getCodecs().getByOid(baseTypeOid, baseType);
  }

  /**
   * Gets the base type for the given domain type.
   */
  private static TypeDescriptor getBaseType(TypeDescriptor domainType, CodecContext ctx) throws SQLException {
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
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeBinary(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeBinary(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).encodeBinary(value, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.encodeBinary(value, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeText(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeText(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).encodeText(value, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.encodeText(value, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeBinaryAs(data, baseType, targetClass, ctx);
      }
      return FallbackCodec.INSTANCE.decodeBinaryAs(data, baseType, targetClass, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeTextAs(data, baseType, targetClass, ctx);
      }
      return FallbackCodec.INSTANCE.decodeTextAs(data, baseType, targetClass, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public int decodeAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeAsInt(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsInt(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeAsInt(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsInt(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public long decodeAsLong(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeAsLong(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsLong(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public long decodeAsLong(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeAsLong(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsLong(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public double decodeAsDouble(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeAsDouble(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsDouble(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public double decodeAsDouble(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeAsDouble(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsDouble(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeAsBigDecimal(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsBigDecimal(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeAsBigDecimal(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsBigDecimal(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeAsString(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsString(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeAsString(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsString(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }
}
