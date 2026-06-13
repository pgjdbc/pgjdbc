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
    ArrayLeafCodec fastLeaf = fastLeafFor(type, ctx);
    if (fastLeaf != null) {
      return MultiDimArrayBinary.encode(javaArray, ctx, fastLeaf);
    }
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
   * Resolves the fast leaf for {@code arrayType}'s element type, or {@code null}
   * when the element codec offers no specialization and the array must fall back
   * to the generic / legacy path.
   *
   * <p>A scalar codec opts in by implementing {@link ArrayElementCodec}; the leaf
   * keeps the per-element loop typed (for example {@code int[]} / {@code Integer[]})
   * so primitive arrays avoid boxing.</p>
   */
  private static @Nullable ArrayLeafCodec fastLeafFor(PgType arrayType, CodecContext ctx)
      throws SQLException {
    int elementOid = arrayType.getTypelem();
    if (elementOid == 0) {
      return null;
    }
    PgType elementType = ctx.getTypeInfo().getPgTypeByOid(elementOid);
    BinaryCodec elementCodec = ctx.getCodecs().getBinaryCodec(elementOid, elementType);
    if (elementCodec instanceof ArrayElementCodec) {
      return ((ArrayElementCodec) elementCodec).arrayLeaf();
    }
    return null;
  }

  /**
   * Encodes a generic {@code Object[]} (any rank) by dispatching each leaf
   * element through the registered binary codec for the array's element type.
   * Used for element types that {@link ArrayEncoding} cannot encode natively
   * (composite, SQLData, domain over composite, etc.). Multi-dim header /
   * dimension walking is shared via {@link MultiDimArrayBinary}.
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
      ArrayLeafCodec fastLeaf = fastLeafFor(type, ctx);
      if (fastLeaf != null) {
        MultiDimArrayText.encode(value, type.getDelimiter(), out, ctx, fastLeaf);
        return;
      }
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

  /**
   * Returns the Java array component type for a non-fast-leaf element decoded
   * through the generic walker, or {@code null} when the element type is not
   * routed there (and must use the legacy decoder).
   *
   * <ul>
   *   <li>composite / range → {@code Object[]} (the shape the legacy decoder
   *       produced for these);</li>
   *   <li>elements whose codec decodes to {@code String} (text, varchar, bpchar,
   *       name), {@code BigDecimal} (numeric), {@code UUID} (uuid) or
   *       {@code byte[]} (bytea, giving {@code byte[][]}) → the matching typed
   *       array.</li>
   * </ul>
   */
  private static @Nullable Class<?> genericComponentType(PgType elementType,
      @Nullable Codec elementCodec) {
    if (elementType.isComposite() || elementType.getTyptype() == 'r') {
      return Object.class;
    }
    if (elementCodec != null) {
      // Allowlist of element Java types whose generic decode matches the legacy
      // getArray() shape. Other types (PGobject for json/jsonb, the temporal
      // types, ...) still take the legacy path.
      Class<?> javaType = elementCodec.getDefaultJavaType();
      if (javaType == String.class
          || javaType == java.math.BigDecimal.class
          || javaType == java.util.UUID.class
          || javaType == byte[].class) {
        return javaType;
      }
    }
    return null;
  }

  /**
   * Decodes a binary array whose element type has no primitive fast leaf
   * (composite, range, string, and other user-defined types) through the shared
   * {@link MultiDimArrayBinary} walker, dispatching each element to the element
   * type's {@link BinaryCodec} from a borrowed slice. The leaf component type is
   * {@code componentType} (for example {@code Object} for composite/range,
   * {@code String} for string types), so the result matches the legacy
   * {@code getArray()} shape while skipping the per-element {@code byte[]} copy.
   */
  private static Object decodeBinaryGeneric(byte[] data, PgType arrayType, Class<?> componentType,
      CodecContext ctx) throws SQLException {
    int elementOid = arrayType.getTypelem();
    PgType elementType = ctx.getTypeInfo().getPgTypeByOid(elementOid);
    BinaryCodec elementCodec = ctx.getCodecs().getBinaryCodec(elementOid, elementType);
    if (elementCodec == null) {
      throw new PSQLException(
          GT.tr("No binary codec registered for array element oid {0}", elementOid),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    GenericArrayLeafCodec leaf = new GenericArrayLeafCodec(elementType, elementCodec, null);
    return MultiDimArrayBinary.decode(data, componentType, ctx, leaf);
  }

  /** Text counterpart of {@link #decodeBinaryGeneric}. */
  private static Object decodeTextGeneric(String data, PgType arrayType, Class<?> componentType,
      CodecContext ctx) throws SQLException {
    int elementOid = arrayType.getTypelem();
    PgType elementType = ctx.getTypeInfo().getPgTypeByOid(elementOid);
    TextCodec elementCodec = ctx.getCodecs().getTextCodec(elementOid, elementType);
    if (elementCodec == null) {
      throw new PSQLException(
          GT.tr("No text codec registered for array element oid {0}", elementOid),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    GenericArrayLeafCodec leaf = new GenericArrayLeafCodec(elementType, null, elementCodec);
    return MultiDimArrayText.decode(data, componentType, arrayType.getDelimiter(), ctx, leaf);
  }

  /**
   * Returns whether {@code arrayType}'s elements decode through the shared codec
   * walker: a primitive fast leaf ({@code int4}, {@code int8}, ...) yielding a
   * typed array, a composite/range element decoded into {@code Object[]}, or a
   * string element decoded into {@code String[]}. Element types with none of these
   * still use the legacy decoder.
   */
  public static boolean canDecodeArrayViaWalker(PgType arrayType, CodecContext ctx)
      throws SQLException {
    if (fastLeafFor(arrayType, ctx) != null) {
      return true;
    }
    int elementOid = arrayType.getTypelem();
    if (elementOid == 0) {
      return false;
    }
    PgType elementType = ctx.getTypeInfo().getPgTypeByOid(elementOid);
    Codec elementCodec = ctx.getCodecs().getByOid(elementOid, elementType);
    return genericComponentType(elementType, elementCodec) != null;
  }

  /**
   * Decodes a binary array through the shared {@link MultiDimArrayBinary} walker:
   * the element's fast leaf (producing a typed array such as {@code Long[]}, the
   * same type the legacy decoder returned) when available, otherwise the generic
   * path producing {@code Object[]} (composite/range) or {@code String[]} (string
   * types). Gate on {@link #canDecodeArrayViaWalker}.
   *
   * @param data the binary array payload
   * @param arrayType the array type metadata
   * @param ctx the codec context
   * @return the decoded array
   * @throws SQLException if decoding fails
   */
  public static Object decodeBinaryArray(byte[] data, PgType arrayType, CodecContext ctx)
      throws SQLException {
    ArrayLeafCodec fast = fastLeafFor(arrayType, ctx);
    if (fast != null) {
      return MultiDimArrayBinary.decode(data, fast.getBoxedComponentType(), ctx, fast);
    }
    PgType elementType = ctx.getTypeInfo().getPgTypeByOid(arrayType.getTypelem());
    Codec elementCodec = ctx.getCodecs().getByOid(arrayType.getTypelem(), elementType);
    Class<?> componentType = genericComponentType(elementType, elementCodec);
    return decodeBinaryGeneric(data, arrayType,
        componentType != null ? componentType : Object.class, ctx);
  }

  /**
   * Text counterpart of {@link #decodeBinaryArray}.
   *
   * @param data the array text literal
   * @param arrayType the array type metadata
   * @param ctx the codec context
   * @return the decoded array
   * @throws SQLException if decoding fails
   */
  public static Object decodeTextArray(String data, PgType arrayType, CodecContext ctx)
      throws SQLException {
    ArrayLeafCodec fast = fastLeafFor(arrayType, ctx);
    if (fast != null) {
      return MultiDimArrayText.decode(data, fast.getBoxedComponentType(),
          arrayType.getDelimiter(), ctx, fast);
    }
    PgType elementType = ctx.getTypeInfo().getPgTypeByOid(arrayType.getTypelem());
    Codec elementCodec = ctx.getCodecs().getByOid(arrayType.getTypelem(), elementType);
    Class<?> componentType = genericComponentType(elementType, elementCodec);
    return decodeTextGeneric(data, arrayType,
        componentType != null ? componentType : Object.class, ctx);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Array.class || targetClass == PgArray.class || targetClass == Object.class) {
      return (T) decodeBinary(data, type, ctx);
    }
    if (targetClass.isArray()) {
      ArrayLeafCodec fastLeaf = fastLeafFor(type, ctx);
      if (fastLeaf != null) {
        Class<?> leafComponentType = MultiDimArraySupport.leafComponentType(targetClass);
        if (fastLeaf.supportsTargetComponent(leafComponentType)) {
          return (T) MultiDimArrayBinary.decode(data, leafComponentType, ctx, fastLeaf);
        }
      }
      // Fall back to the legacy decoder for element types without a fast leaf.
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
      ArrayLeafCodec fastLeaf = fastLeafFor(type, ctx);
      if (fastLeaf != null) {
        Class<?> leafComponentType = MultiDimArraySupport.leafComponentType(targetClass);
        if (fastLeaf.supportsTargetComponent(leafComponentType)) {
          return (T) MultiDimArrayText.decode(data, leafComponentType, type.getDelimiter(), ctx,
              fastLeaf);
        }
      }
      // Fall back to the legacy decoder for element types without a fast leaf.
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
