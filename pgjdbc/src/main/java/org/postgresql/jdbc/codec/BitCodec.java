/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.BooleanTypeUtil;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Codec for PostgreSQL {@code bit} and {@code bit varying} (varbit) types.
 *
 * <p>Both share the wire format: text is a string of {@code '0'}/{@code '1'} (for example
 * {@code "0101"}); binary is an int4 bit count followed by the bits packed MSB-first into
 * {@code ceil(n/8)} bytes.</p>
 *
 * <p>{@code getObject()} on a scalar {@code bit} column is special-cased in {@code PgResultSet}
 * ({@code bit(1)} → {@link Boolean}, wider → {@link PGobject}), so this codec's default
 * representation is the {@link PGobject} bit string, matching the legacy fallback. On encode it also
 * accepts a {@link Boolean} ({@code "1"}/{@code "0"}), so a {@code boolean[]} binds correctly to
 * {@code bit[]}.</p>
 *
 * <p>Binary is fully supported in both directions: {@link #decodeBinary} parses the packed wire form
 * and {@link #encodeBinary} produces it. The packed binary is ~8× smaller than the text form, so
 * {@code bit}/{@code varbit} (scalars and arrays) are registered for binary transfer like the other
 * built-in types. The scalar {@code bit(1) → Boolean} contract is preserved by reading the bit count
 * from the int4 prefix when the value arrives in binary (see {@code PgResultSet}); array elements are
 * always {@link PGobject}, so {@code bit[]}/{@code varbit[]} decode to {@code PGobject[]} via the
 * array codec walker.</p>
 */
public final class BitCodec implements PrimitiveBinaryDecoder, PrimitiveTextDecoder {

  public static final BitCodec INSTANCE = new BitCodec();

  private BitCodec() {
  }

  @Override
  public String getTypeName() {
    return "bit";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGobject.class;
  }

  // ----------------------------- decode -----------------------------

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return toPGobject(type, data);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return toPGobject(type, binaryToBitString(data, 0, data.length));
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return toPGobject(type, binaryToBitString(data, offset, length));
  }

  private static PGobject toPGobject(TypeDescriptor type, String bits) throws SQLException {
    PGobject obj = new PGobject();
    obj.setType(type.getTypeName().getName());
    obj.setValue(bits);
    return obj;
  }

  @Override
  public String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return binaryToBitString(data, 0, data.length);
  }

  @Override
  public boolean decodeAsBoolean(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return BooleanTypeUtil.fromString(data);
  }

  @Override
  public boolean decodeAsBoolean(char[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return BooleanTypeUtil.fromString(new String(data, offset, length));
  }

  @Override
  public boolean decodeAsBoolean(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return BooleanTypeUtil.fromString(binaryToBitString(data, offset, length));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == String.class) {
      return (T) data;
    }
    if (targetClass == Boolean.class) {
      return (T) Boolean.valueOf(BooleanTypeUtil.fromString(data));
    }
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) toPGobject(type, data);
    }
    throw Codec.cannotDecode(type.getTypeName().getName(), targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    String bits = binaryToBitString(data, 0, data.length);
    if (targetClass == String.class) {
      return (T) bits;
    }
    if (targetClass == Boolean.class) {
      return (T) Boolean.valueOf(BooleanTypeUtil.fromString(bits));
    }
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) toPGobject(type, bits);
    }
    throw Codec.cannotDecode(type.getTypeName().getName(), targetClass.getName());
  }

  // ----------------------------- encode -----------------------------

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return toBitString(value);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return bitStringToBinary(toBitString(value));
  }

  private static String toBitString(Object value) throws SQLException {
    if (value instanceof Boolean) {
      return (Boolean) value ? "1" : "0";
    }
    if (value instanceof PGobject) {
      String v = ((PGobject) value).getValue();
      return v != null ? v : "";
    }
    if (value instanceof String) {
      return (String) value;
    }
    throw Codec.cannotEncode(value, "bit");
  }

  // ------------------------ binary <-> bit string ------------------------

  private static String binaryToBitString(byte[] data, int offset, int length) throws SQLException {
    if (length < 4) {
      throw new PSQLException(
          GT.tr("Invalid bit binary data length: {0}", length), PSQLState.DATA_ERROR);
    }
    int nbits = ByteConverter.int4(data, offset);
    // The wire form is a 4-byte bit count followed by ceil(nbits/8) packed bytes. Validate the count
    // against the bytes actually present before allocating the StringBuilder or walking the packed
    // body: a negative or oversized count read from untrusted or corrupt wire would otherwise drive an
    // OutOfMemoryError on the allocation or an ArrayIndexOutOfBoundsException in the loop. The server
    // never emits a mismatched length, so reject it with DATA_ERROR. The ceil is computed in long to
    // avoid the (nbits + 7) overflow near Integer.MAX_VALUE.
    long expectedBytes = 4L + (nbits + 7L) / 8L;
    if (nbits < 0 || expectedBytes != length) {
      throw new PSQLException(
          GT.tr("Invalid bit binary data: bit count {0} does not match data length {1}",
              nbits, length),
          PSQLState.DATA_ERROR);
    }
    StringBuilder sb = new StringBuilder(nbits);
    for (int i = 0; i < nbits; i++) {
      int b = data[offset + 4 + (i >> 3)];
      sb.append(((b >> (7 - (i & 7))) & 1) == 1 ? '1' : '0');
    }
    return sb.toString();
  }

  private static byte[] bitStringToBinary(String bits) {
    int nbits = bits.length();
    byte[] out = new byte[4 + (nbits + 7) / 8];
    ByteConverter.int4(out, 0, nbits);
    for (int i = 0; i < nbits; i++) {
      if (bits.charAt(i) == '1') {
        out[4 + (i >> 3)] |= (byte) (1 << (7 - (i & 7)));
      }
    }
    return out;
  }
}
