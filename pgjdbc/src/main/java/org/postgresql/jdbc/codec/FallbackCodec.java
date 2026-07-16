/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.TemporalCodecs;
import org.postgresql.util.PGUnknownBinary;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Arrays;

/**
 * Fallback codec for unknown/unmapped PostgreSQL types.
 *
 * <p>Returns values as {@link PGobject} for text format.
 * For binary format, stores raw bytes in PGobject.</p>
 */
public final class FallbackCodec implements PrimitiveBinaryDecoder, PrimitiveTextDecoder {

  public static final FallbackCodec INSTANCE = new FallbackCodec();

  private FallbackCodec() {
    // Singleton
  }

  @Override
  public String getPrimaryTypeName() {
    return "unknown";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGobject.class;
  }

  @Override
  public boolean decodesBinary() {
    // The fallback does not interpret the real binary wire of an unmapped type; it only wraps
    // the raw bytes. Reporting false makes the receive-format choice request such a type in text,
    // so it arrives as a readable PGobject (decodeText) rather than PGUnknownBinary. Binary data
    // still reaches decodeBinary when it is unavoidable -- e.g. an unmapped field nested in a
    // record the driver requested in binary -- and is surfaced as PGUnknownBinary there.
    return false;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    // Reached only when the server sends binary for an unmapped type anyway (e.g. a field inside a
    // binary record): preserve the raw bytes as PGUnknownBinary instead of a lossy hex string. The
    // value owns its bytes, so copy only for a genuine sub-slice.
    byte[] bytes = offset == 0 && length == data.length ? data : Arrays.copyOfRange(data, offset, offset + length);
    return new PGUnknownBinary(type.getTypeName().getName(), bytes);
  }

  @Override
  public boolean canEncodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // Only PGUnknownBinary carries a genuine binary payload -- its bytes were read from the server's
    // binary output for this OID, so re-sending them as binary is a faithful round-trip. A String has
    // no known binary wire form for an arbitrary unmapped type: its charset bytes are the text wire,
    // which differs from the type's binary recv format (ltree, say, prefixes a version byte). Refusing
    // String here makes the bind negotiation fall back to text, the format the driver can produce
    // correctly, instead of mislabelling charset bytes as binary.
    return value instanceof PGUnknownBinary;
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof PGUnknownBinary) {
      byte[] bytes = ((PGUnknownBinary) value).getBytes();
      return bytes != null ? bytes : new byte[0];
    }
    // A String reaches here only if a caller bypasses canEncodeBinary/the format negotiation and forces
    // binary. The fallback cannot produce the binary wire of an unmapped type, so refuse rather than
    // emit charset (text) bytes under a binary label.
    throw Exceptions.cannotEncode(value, type.getTypeName().getName());
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGobject obj = new PGobject();
    obj.setType(type.getTypeName().getName());
    obj.setValue(data);
    return obj;
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof PGobject) {
      String strValue = ((PGobject) value).getValue();
      return strValue != null ? strValue : "";
    }
    if (value instanceof String) {
      return (String) value;
    }
    return value.toString();
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return new String(data, offset, length, ctx.getCharset());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass == PGUnknownBinary.class || targetClass == Object.class) {
      return (T) decodeBinary(data, offset, length, type, ctx);
    }
    if (targetClass == PGobject.class) {
      // Return text-based PGobject for compatibility
      PGobject obj = new PGobject();
      obj.setType(type.getTypeName().getName());
      obj.setValue(decodeAsString(data, offset, length, type, ctx));
      return (T) obj;
    }
    if (targetClass == String.class) {
      return (T) decodeAsString(data, offset, length, type, ctx);
    }
    if (targetClass == byte[].class) {
      return (T) Arrays.copyOfRange(data, offset, offset + length);
    }
    throw Exceptions.cannotDecode(type.getTypeName().getName(), targetClass.getName());
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    // 'unknown' (oid 705, e.g. SELECT '2024-01-01' without a cast) and any other unmapped text
    // value is parsed as a date/time literal, matching how the legacy fallback handled any
    // text column. Mapped non-string types (json, xml, ...) carry their own codec and never
    // reach here, so they keep rejecting date/time coercion.
    if (targetClass == Date.class) {
      return (T) TemporalCodecs.decodeDateText(data, ctx);
    }
    if (targetClass == Time.class) {
      return (T) TemporalCodecs.decodeTimeText(data, ctx);
    }
    if (targetClass == Timestamp.class) {
      return (T) TemporalCodecs.decodeTimestampText(data, ctx);
    }
    throw Exceptions.cannotDecode(type.getTypeName().getName(), targetClass.getName());
  }

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    String s = new String(data, offset, length, ctx.getCharset());
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("int", s, e);
    }
  }

  @Override
  public int decodeAsInt(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String text = data.toString();
    try {
      return Integer.parseInt(text.trim());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("int", text, e);
    }
  }

  @Override
  public long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    String s = new String(data, offset, length, ctx.getCharset());
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("long", s, e);
    }
  }

  @Override
  public long decodeAsLong(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String text = data.toString();
    try {
      return Long.parseLong(text.trim());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("long", text, e);
    }
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    String s = new String(data, offset, length, ctx.getCharset());
    try {
      return Double.parseDouble(s.trim());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("double", s, e);
    }
  }

  @Override
  public double decodeAsDouble(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String text = data.toString();
    try {
      return Double.parseDouble(text.trim());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("double", text, e);
    }
  }

  // float mirrors decodeAsDouble: the raw text is parsed as a number and narrowed. Without these the
  // default decodeAsFloat would box decodeBinary's PGUnknownBinary (not a Number) and refuse, so
  // getFloat would fail on a numeric-text value that getDouble reads -- an inconsistency.
  @Override
  public float decodeAsFloat(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return (float) decodeAsDouble(data, offset, length, type, ctx);
  }

  @Override
  public float decodeAsFloat(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return (float) decodeAsDouble(data, type, ctx);
  }

}
