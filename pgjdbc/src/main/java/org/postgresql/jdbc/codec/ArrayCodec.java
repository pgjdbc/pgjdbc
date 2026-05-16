/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc.ArrayDecoding;
import org.postgresql.jdbc.ArrayEncoding;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.CodecDepth;
import org.postgresql.jdbc.PgArray;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Array;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL array types.
 *
 * <p>This codec handles encoding and decoding of PostgreSQL arrays by delegating
 * to {@link ArrayEncoding} and {@link ArrayDecoding} utilities. For decoding,
 * it returns a {@link PgArray} that lazily decodes elements on access. For encoding,
 * it accepts both {@link Array} (PgArray) and raw Java array objects.</p>
 */
public final class ArrayCodec implements StreamingBinaryCodec, StreamingTextCodec {

  public static final ArrayCodec INSTANCE = new ArrayCodec();

  private ArrayCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "array";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Array.class;
  }

  /**
   * Validates a Java array for PostgreSQL array serialization without encoding it.
   *
   * <p>The array must be rectangular, and intermediate array levels must not
   * contain {@code null}. Leaf values may be {@code null}.</p>
   *
   * @param javaArray Java array to validate
   * @throws SQLException if the value is not an array, is jagged, or contains
   *         {@code null} at an intermediate array level
   */
  public static void validateJavaArray(Object javaArray) throws SQLException {
    int dimensions = MultiDimArraySupport.computeDimensions(javaArray);
    if (dimensions == 0) {
      throw new PSQLException(
          GT.tr("Cannot convert {0} to array", javaArray.getClass().getName()),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    MultiDimArraySupport.computeDimensionLengths(javaArray, dimensions);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    // Return a PgArray wrapping the binary data for lazy decoding
    BaseConnection conn = ctx.getConnection();
    return new PgArray(conn, type.getOid(), data);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (value instanceof PgArray) {
      PgArray pgArray = (PgArray) value;
      if (pgArray.isBinary()) {
        byte[] bytes = pgArray.toBytes();
        if (bytes != null) {
          return bytes;
        }
      }
      // Text-mode PgArray: decode to Java array, then re-encode as binary
      Object javaArray = pgArray.getArray();
      if (javaArray != null) {
        return encodeBinaryJavaArray(javaArray, type, ctx);
      }
      return new byte[0];
    }
    if (value instanceof Array) {
      // Generic JDBC Array - get the underlying array and encode
      Object javaArray = ((Array) value).getArray();
      if (javaArray != null) {
        return encodeBinaryJavaArray(javaArray, type, ctx);
      }
      return new byte[0];
    }
    if (value.getClass().isArray()) {
      return encodeBinaryJavaArray(value, type, ctx);
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to array", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @SuppressWarnings("unchecked")
  private byte[] encodeBinaryJavaArray(Object javaArray, PgType type, CodecContext ctx) throws SQLException {
    int arrayOid = type.getOid();
    if (ArrayEncoding.hasNativeEncoder(javaArray)) {
      ArrayEncoding.ArrayEncoder<Object> encoder = ArrayEncoding.getArrayEncoder(javaArray);
      if (encoder.supportBinaryRepresentation(arrayOid)) {
        return encoder.toBinaryRepresentation(ctx.getConnection(), javaArray, arrayOid);
      }
    }
    if (!(javaArray instanceof Object[])) {
      // No native encoder and not an Object[] (e.g. a primitive multi-dim array
      // whose oid we do not support binary-wise). Defer to ArrayEncoding so it
      // produces the same error message it always has.
      ArrayEncoding.ArrayEncoder<Object> encoder = ArrayEncoding.getArrayEncoder(javaArray);
      return encoder.toBinaryRepresentation(ctx.getConnection(), javaArray, arrayOid);
    }
    BackpatchByteArrayOutputStream out = new BackpatchByteArrayOutputStream();
    try {
      streamBinaryArrayViaCodec(javaArray, type, ctx, out);
    } catch (IOException e) {
      // BackpatchByteArrayOutputStream never throws.
      throw new AssertionError(e);
    }
    return out.toByteArray();
  }

  /**
   * Encodes a generic {@code Object[]} (any rank) by dispatching each leaf
   * element through the registered binary codec for the array's element type.
   * Used for element types that {@link ArrayEncoding} cannot encode natively
   * (composite, SQLData, domain over composite, etc.). Multi-dim header /
   * dimension walking is shared with {@link ArrayLeafStreamingCodec} via
   * {@link MultiDimArrayBinary}.
   */
  private void streamBinaryArrayViaCodec(Object javaArray, PgType arrayType, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    int elementOid = arrayType.getTypelem();
    PgType elementType = ctx.getTypeInfo().getPgTypeByOid(elementOid);
    BinaryCodec elementCodec = ctx.getCodecs().getBinaryCodec(elementOid, elementType);
    if (elementCodec == null) {
      throw new PSQLException(
          GT.tr("No binary codec registered for array element oid {0}", elementOid),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    GenericArrayLeafCodec leafCodec = new GenericArrayLeafCodec(elementType, elementCodec, null);
    MultiDimArrayBinary.encode(javaArray, out, ctx, leafCodec);
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    // Return a PgArray wrapping the text data for lazy decoding
    BaseConnection conn = ctx.getConnection();
    return new PgArray(conn, type.getOid(), data);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    StringBuilder sb = new StringBuilder();
    try {
      encodeText(value, type, ctx, sb);
    } catch (IOException e) {
      throw new AssertionError(e); // StringBuilder never throws
    }
    return sb.toString();
  }

  @Override
  public void encodeBinary(Object value, PgType type, CodecContext ctx, OutputStream out)
      throws SQLException, IOException {
    // Defer to the non-streaming path for unwrap/native-encoder dispatch;
    // the via-codec branch internally back-patches lengths via
    // BackpatchByteArrayOutputStream when the element codec is streaming,
    // so the per-element byte[] is avoided there. The outer byte[] is the
    // payload we hand to out.
    out.write(encodeBinary(value, type, ctx));
  }

  @Override
  public void encodeText(Object value, PgType type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    if (value instanceof Array) {
      String str = value.toString();
      out.append(str != null ? str : "NULL");
      return;
    }
    if (value.getClass().isArray()) {
      if (ArrayEncoding.hasNativeEncoder(value)) {
        @SuppressWarnings("unchecked")
        ArrayEncoding.ArrayEncoder<Object> encoder = ArrayEncoding.getArrayEncoder(value);
        out.append(encoder.toArrayString(type.getDelimiter(), value));
        return;
      }
      if (value instanceof Object[]) {
        streamTextArrayViaCodec(value, type, ctx, out);
        return;
      }
      @SuppressWarnings("unchecked")
      ArrayEncoding.ArrayEncoder<Object> encoder = ArrayEncoding.getArrayEncoder(value);
      out.append(encoder.toArrayString(type.getDelimiter(), value));
      return;
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to array", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  /** Streams a generic {@code Object[]} (any rank) as a PostgreSQL text array literal. */
  private void streamTextArrayViaCodec(Object javaArray, PgType arrayType, CodecContext ctx,
      Appendable out) throws SQLException, IOException {
    int elementOid = arrayType.getTypelem();
    PgType elementType = ctx.getTypeInfo().getPgTypeByOid(elementOid);
    TextCodec elementCodec = ctx.getCodecs().getTextCodec(elementOid, elementType);
    if (elementCodec == null) {
      throw new PSQLException(
          GT.tr("No text codec registered for array element oid {0}", elementOid),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    GenericArrayLeafCodec leafCodec = new GenericArrayLeafCodec(elementType, null, elementCodec);
    MultiDimArrayText.encode(javaArray, arrayType.getDelimiter(), out, ctx, leafCodec);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Array.class || targetClass == PgArray.class || targetClass == Object.class) {
      return (T) decodeBinary(data, type, ctx);
    }
    if (targetClass.isArray()) {
      // Decode binary array to Java array, then the caller gets the typed array
      CodecDepth.enter();
      try {
        BaseConnection conn = ctx.getConnection();
        return (T) ArrayDecoding.readBinaryArray(1, 0, data, conn);
      } finally {
        CodecDepth.exit();
      }
    }
    throw new PSQLException(
        GT.tr("Cannot convert array to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Array.class || targetClass == PgArray.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass.isArray()) {
      // Decode text array to Java array
      CodecDepth.enter();
      try {
        BaseConnection conn = ctx.getConnection();
        ArrayDecoding.PgArrayList arrayList = ArrayDecoding.buildArrayList(data, type.getDelimiter());
        return (T) ArrayDecoding.readStringArray(1, arrayList.size(), type.getTypelem(), arrayList, conn);
      } finally {
        CodecDepth.exit();
      }
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    throw new PSQLException(
        GT.tr("Cannot convert array to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public @Nullable String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    // Preserve the PostgreSQL text representation (e.g. {{1,0},{0,1}});
    // PgArray.toString() would re-emit elements with quotes.
    return data;
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw Codec.cannotConvert("array", "int");
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw Codec.cannotConvert("array", "int");
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw Codec.cannotConvert("array", "long");
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    throw Codec.cannotConvert("array", "long");
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw Codec.cannotConvert("array", "double");
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    throw Codec.cannotConvert("array", "double");
  }
}
