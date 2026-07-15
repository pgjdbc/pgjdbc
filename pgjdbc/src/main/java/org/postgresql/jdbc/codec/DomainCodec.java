/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveDecoders;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.CodecDepth;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
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
 *   <li><strong>The domain's type modifier is propagated on decode.</strong> A domain may pin a
 *   typmod on its base type (for example {@code CREATE DOMAIN price AS numeric(10,2)}), stored in
 *   {@code pg_type.typtypmod}. This codec forwards that modifier to the base type via
 *   {@link TypeDescriptor#withTypmod(int)}, so a modifier-sensitive base codec — numeric rescaling to
 *   the declared scale, for instance — observes it through {@link TypeDescriptor#getTypmod()}. Encode
 *   is unaffected: the numeric codecs encode from the value's own scale and the server enforces the
 *   domain constraint on input regardless. The domain's own {@link TypeDescriptor#getTyptypmod()} is
 *   left unchanged for metadata such as column-size reporting.</li>
 * </ul>
 */
public final class DomainCodec implements StreamingBinaryCodec, StreamingTextCodec,
    PrimitiveBinaryDecoder, PrimitiveTextDecoder {

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
    return ctx.resolveCodec(baseTypeOid);
  }

  /**
   * Gets the base type for the given domain type.
   */
  private static TypeDescriptor getBaseType(TypeDescriptor domainType, CodecContext ctx) throws SQLException {
    int baseTypeOid = domainType.getTypbasetype();
    if (baseTypeOid == 0) {
      return domainType;
    }
    // A domain pins its base type's modifier in pg_type.typtypmod (CREATE DOMAIN price AS
    // numeric(10,2)); a domain column may also arrive with an applied modifier. Forward whichever
    // applies so the base codec can decode a modifier-sensitive base type such as numeric. Encode
    // ignores it (numeric encodes from the value's own scale), so stamping here is decode-only in
    // effect.
    int typmod = domainType.getTypmod() != -1 ? domainType.getTypmod() : domainType.getTyptypmod();
    return ctx.resolveType(baseTypeOid, typmod);
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
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeBinary(data, offset, length, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeBinary(data, offset, length, baseType, ctx);
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
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof StreamingBinaryCodec) {
        ((StreamingBinaryCodec) baseCodec).encodeBinary(value, baseType, ctx, out);
      } else if (baseCodec instanceof BinaryCodec) {
        out.write(((BinaryCodec) baseCodec).encodeBinary(value, baseType, ctx));
      } else {
        out.write(FallbackCodec.INSTANCE.encodeBinary(value, baseType, ctx));
      }
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
  public void encodeText(Object value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof StreamingTextCodec) {
        ((StreamingTextCodec) baseCodec).encodeText(value, baseType, ctx, out);
      } else if (baseCodec instanceof TextCodec) {
        out.append(((TextCodec) baseCodec).encodeText(value, baseType, ctx));
      } else {
        out.append(FallbackCodec.INSTANCE.encodeText(value, baseType, ctx));
      }
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeBinaryAs(data, 0, data.length, baseType, targetClass, ctx);
      }
      return FallbackCodec.INSTANCE.decodeBinaryAs(data, 0, data.length, baseType, targetClass, ctx);
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

  // A domain shares its base type's wire form, so the primitive accessors forward the slice (binary)
  // or string (text) straight to the base codec's own no-box path via PrimitiveDecoders, which boxes
  // through the base's decodeBinary/decodeText only when the base is not itself a primitive decoder.

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return PrimitiveDecoders.asInt((BinaryCodec) baseCodec, data, offset, length, baseType, ctx);
      }
      return PrimitiveDecoders.asInt(FallbackCodec.INSTANCE, data, offset, length, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return PrimitiveDecoders.asLong((BinaryCodec) baseCodec, data, offset, length, baseType, ctx);
      }
      return PrimitiveDecoders.asLong(FallbackCodec.INSTANCE, data, offset, length, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public float decodeAsFloat(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return PrimitiveDecoders.asFloat((BinaryCodec) baseCodec, data, offset, length, baseType, ctx);
      }
      return PrimitiveDecoders.asFloat(FallbackCodec.INSTANCE, data, offset, length, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return PrimitiveDecoders.asDouble((BinaryCodec) baseCodec, data, offset, length, baseType, ctx);
      }
      return PrimitiveDecoders.asDouble(FallbackCodec.INSTANCE, data, offset, length, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public boolean decodeAsBoolean(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return PrimitiveDecoders.asBoolean((BinaryCodec) baseCodec, data, offset, length, baseType, ctx);
      }
      return PrimitiveDecoders.asBoolean(FallbackCodec.INSTANCE, data, offset, length, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public int decodeAsInt(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return PrimitiveDecoders.asInt((TextCodec) baseCodec, data, baseType, ctx);
      }
      return PrimitiveDecoders.asInt(FallbackCodec.INSTANCE, data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public long decodeAsLong(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return PrimitiveDecoders.asLong((TextCodec) baseCodec, data, baseType, ctx);
      }
      return PrimitiveDecoders.asLong(FallbackCodec.INSTANCE, data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public float decodeAsFloat(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return PrimitiveDecoders.asFloat((TextCodec) baseCodec, data, baseType, ctx);
      }
      return PrimitiveDecoders.asFloat(FallbackCodec.INSTANCE, data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public double decodeAsDouble(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return PrimitiveDecoders.asDouble((TextCodec) baseCodec, data, baseType, ctx);
      }
      return PrimitiveDecoders.asDouble(FallbackCodec.INSTANCE, data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public boolean decodeAsBoolean(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return PrimitiveDecoders.asBoolean((TextCodec) baseCodec, data, baseType, ctx);
      }
      return PrimitiveDecoders.asBoolean(FallbackCodec.INSTANCE, data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return PrimitiveDecoders.asBigDecimal((BinaryCodec) baseCodec, data, offset, length, baseType, ctx);
      }
      return PrimitiveDecoders.asBigDecimal(FallbackCodec.INSTANCE, data, offset, length, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return PrimitiveDecoders.asBigDecimal((TextCodec) baseCodec, data, baseType, ctx);
      }
      return PrimitiveDecoders.asBigDecimal(FallbackCodec.INSTANCE, data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeAsString(data, offset, length, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsString(data, offset, length, baseType, ctx);
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
