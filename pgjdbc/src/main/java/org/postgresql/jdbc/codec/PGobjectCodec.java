/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.GT;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Adapter codec for a {@link PGobject} subclass registered through
 * {@link org.postgresql.PGConnection#addDataType(String, Class)}.
 *
 * <p>This decorates the codec that would otherwise handle the type (the
 * <em>delegate</em>) and overrides only the object-producing decode paths: a
 * decoded value is materialized as the registered {@code PGobject} subclass,
 * populated through {@link PGBinaryObject#setByteValue(byte[], int)} when the
 * class supports binary and the data is binary, otherwise through
 * {@link PGobject#setValue(String)} with the value's text literal. Every other
 * operation — encoding, {@code getString}/{@code getInt}-style coercions,
 * {@code SQLData} targets — is forwarded to the delegate unchanged.</p>
 *
 * <p>Registering the adapter by OID makes it apply wherever the codec layer
 * resolves the type: top-level columns, array elements, and composite fields.
 * It is keyed by OID, so the registered identifier form (bare, schema-qualified,
 * or quoted) no longer matters for resolution.</p>
 */
public final class PGobjectCodec implements BinaryCodec, TextCodec {

  private final Class<? extends PGobject> pgObjectClass;
  private final Codec delegate;
  private final boolean binaryObject;

  /**
   * Creates an adapter for {@code pgObjectClass} backed by {@code delegate}.
   *
   * @param pgObjectClass the registered PGobject subclass
   * @param delegate the codec that would otherwise handle the type
   */
  public PGobjectCodec(Class<? extends PGobject> pgObjectClass, Codec delegate) {
    this.pgObjectClass = pgObjectClass;
    this.delegate = delegate;
    this.binaryObject = PGBinaryObject.class.isAssignableFrom(pgObjectClass);
  }

  @Override
  public String getTypeName() {
    return delegate.getTypeName();
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return pgObjectClass;
  }

  // ---- Decode: produce the registered PGobject subclass --------------------

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    return fromText(data, type);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (binaryObject) {
      return fromBinary(data, type);
    }
    // A non-binary PGobject subclass is populated from text, so render the
    // binary wire through the delegate first.
    String text = delegate instanceof BinaryCodec
        ? ((BinaryCodec) delegate).decodeAsString(data, type, ctx)
        : null;
    return text == null ? null : fromText(text, type);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass.isAssignableFrom(pgObjectClass)) {
      return (T) fromText(data, type);
    }
    if (delegate instanceof TextCodec) {
      return ((TextCodec) delegate).decodeTextAs(data, type, targetClass, ctx);
    }
    throw Codec.cannotDecode(getTypeName(), targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass.isAssignableFrom(pgObjectClass)) {
      return (T) decodeBinary(data, type, ctx);
    }
    if (delegate instanceof BinaryCodec) {
      return ((BinaryCodec) delegate).decodeBinaryAs(data, type, targetClass, ctx);
    }
    throw Codec.cannotDecode(getTypeName(), targetClass.getName());
  }

  // ---- Encode and coercions: forward to the delegate -----------------------

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (delegate instanceof TextCodec) {
      return ((TextCodec) delegate).encodeText(value, type, ctx);
    }
    throw Codec.cannotEncode(value, type.getFullName());
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (delegate instanceof BinaryCodec) {
      return ((BinaryCodec) delegate).encodeBinary(value, type, ctx);
    }
    throw Codec.cannotEncode(value, type.getFullName());
  }

  @Override
  public boolean supportsBinaryEncoding() {
    return delegate instanceof BinaryCodec && ((BinaryCodec) delegate).supportsBinaryEncoding();
  }

  @Override
  public boolean canEncodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    return delegate instanceof BinaryCodec && ((BinaryCodec) delegate).canEncodeBinary(value, type, ctx);
  }

  @Override
  public boolean mayRequireQuoting() {
    return !(delegate instanceof TextCodec) || ((TextCodec) delegate).mayRequireQuoting();
  }

  @Override
  public @Nullable String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    if (delegate instanceof TextCodec) {
      return ((TextCodec) delegate).decodeAsString(data, type, ctx);
    }
    return data;
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (delegate instanceof BinaryCodec) {
      return ((BinaryCodec) delegate).decodeAsString(data, type, ctx);
    }
    throw Codec.cannotDecode(getTypeName(), "String");
  }

  // ---- Materialization -----------------------------------------------------

  private PGobject newInstance(PgType type) throws SQLException {
    PGobject obj;
    try {
      obj = pgObjectClass.getConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new PSQLException(
          GT.tr("Cannot create instance of {0}. An accessible no-arg constructor is required.",
              pgObjectClass.getName()),
          PSQLState.SYSTEM_ERROR, e);
    }
    obj.setType(type.getFullName());
    return obj;
  }

  private PGobject fromText(String text, PgType type) throws SQLException {
    PGobject obj = newInstance(type);
    obj.setValue(text);
    return obj;
  }

  private PGobject fromBinary(byte[] data, PgType type) throws SQLException {
    PGobject obj = newInstance(type);
    ((PGBinaryObject) obj).setByteValue(data, 0);
    return obj;
  }
}
