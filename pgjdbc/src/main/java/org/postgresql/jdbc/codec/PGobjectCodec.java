/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.CodecFormatSupport;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
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
public final class PGobjectCodec implements StreamingBinaryCodec, StreamingTextCodec {

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
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return fromText(data, type);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (binaryObject) {
      return fromBinary(data, offset, type);
    }
    // A non-binary PGobject subclass is populated from text, so render the
    // binary wire through the delegate first.
    String text = delegate instanceof BinaryCodec
        ? ((BinaryCodec) delegate).decodeAsString(data, offset, length, type, ctx)
        : null;
    return text == null ? null : fromText(text, type);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass.isAssignableFrom(pgObjectClass)) {
      return (T) fromText(data, type);
    }
    if (delegate instanceof TextCodec) {
      return ((TextCodec) delegate).decodeTextAs(data, type, targetClass, ctx);
    }
    throw Exceptions.cannotDecode(getTypeName(), targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass.isAssignableFrom(pgObjectClass)) {
      return (T) decodeBinary(data, offset, length, type, ctx);
    }
    if (delegate instanceof BinaryCodec) {
      return ((BinaryCodec) delegate).decodeBinaryAs(data, offset, length, type, targetClass, ctx);
    }
    throw Exceptions.cannotDecode(getTypeName(), targetClass.getName());
  }

  // ---- Encode and coercions: forward to the delegate -----------------------

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (delegate instanceof TextCodec) {
      return ((TextCodec) delegate).encodeText(value, type, ctx);
    }
    throw Exceptions.cannotEncode(value, type.getFullName());
  }

  @Override
  public void encodeText(Object value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    if (delegate instanceof StreamingTextCodec) {
      ((StreamingTextCodec) delegate).encodeText(value, type, ctx, out);
    } else if (delegate instanceof TextCodec) {
      out.append(((TextCodec) delegate).encodeText(value, type, ctx));
    } else {
      throw Exceptions.cannotEncode(value, type.getFullName());
    }
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (delegate instanceof BinaryCodec) {
      return ((BinaryCodec) delegate).encodeBinary(value, type, ctx);
    }
    throw Exceptions.cannotEncode(value, type.getFullName());
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    if (delegate instanceof StreamingBinaryCodec) {
      ((StreamingBinaryCodec) delegate).encodeBinary(value, type, ctx, out);
    } else if (delegate instanceof BinaryCodec) {
      out.write(((BinaryCodec) delegate).encodeBinary(value, type, ctx));
    } else {
      throw Exceptions.cannotEncode(value, type.getFullName());
    }
  }

  @Override
  public boolean encodesBinary() {
    return delegate instanceof BinaryCodec && ((BinaryCodec) delegate).encodesBinary();
  }

  @Override
  public boolean canEncodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return delegate instanceof BinaryCodec && ((BinaryCodec) delegate).canEncodeBinary(value, type, ctx);
  }

  @Override
  public boolean decodesBinary() {
    // A PGBinaryObject subclass reads the binary wire itself (fromBinary); any other subclass renders
    // binary only by delegating to the underlying codec, so it reads binary exactly when the delegate
    // does. Without this the adapter would inherit the default true and claim a binary receive its
    // text-only delegate cannot honour, decoding a non-null value as null.
    return binaryObject || CodecFormatSupport.canReadBinary(delegate);
  }

  @Override
  public boolean mayRequireQuoting() {
    return !(delegate instanceof TextCodec) || ((TextCodec) delegate).mayRequireQuoting();
  }

  @Override
  public @Nullable String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (delegate instanceof TextCodec) {
      return ((TextCodec) delegate).decodeAsString(data, type, ctx);
    }
    return data;
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (delegate instanceof BinaryCodec) {
      return ((BinaryCodec) delegate).decodeAsString(data, offset, length, type, ctx);
    }
    throw Exceptions.cannotDecode(getTypeName(), "String");
  }

  // ---- Materialization -----------------------------------------------------

  private PGobject newInstance(TypeDescriptor type) throws SQLException {
    PGobject obj;
    try {
      obj = pgObjectClass.getConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw Exceptions.cannotInstantiate(pgObjectClass.getName(), e);
    }
    obj.setType(type.getFullName());
    return obj;
  }

  private PGobject fromText(String text, TypeDescriptor type) throws SQLException {
    PGobject obj = newInstance(type);
    obj.setValue(text);
    return obj;
  }

  private PGobject fromBinary(byte[] data, int offset, TypeDescriptor type) throws SQLException {
    PGobject obj = newInstance(type);
    ((PGBinaryObject) obj).setByteValue(data, offset);
    return obj;
  }
}
