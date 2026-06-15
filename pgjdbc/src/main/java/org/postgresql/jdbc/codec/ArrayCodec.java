/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
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
    validateJavaArray(javaArray, null);
  }

  /**
   * Validates a Java array whose SQL element type's Java representation is
   * {@code leafElementClass}, so an array-typed element (a {@code byte[]} for
   * {@code bytea}) is treated as a leaf rather than an inner dimension. This
   * lets {@code bytea[]} hold {@code byte[]} elements of differing lengths.
   * A {@code null} or non-array {@code leafElementClass} behaves like
   * {@link #validateJavaArray(Object)}.
   *
   * @param javaArray Java array to validate
   * @param leafElementClass the Java class of one SQL element, or {@code null}
   * @throws SQLException if the value is not an array, is jagged, or contains
   *         {@code null} at an intermediate array level
   */
  public static void validateJavaArray(Object javaArray, @Nullable Class<?> leafElementClass)
      throws SQLException {
    int dimensions = MultiDimArraySupport.computeDimensions(javaArray, leafElementClass);
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
  private static byte[] encodeBinaryJavaArray(Object javaArray, PgType type, CodecContext ctx) throws SQLException {
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
      MultiDimArrayBinary.encode(javaArray, (BackpatchingBinarySink) out, ctx,
          getGenericArrayLeafCodec(type, ctx));
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
   * Returns whether {@code value}'s array can be bound as a true PostgreSQL binary payload. The
   * array's element type must itself support binary encoding (the {@code time}/{@code timetz}/
   * {@code timestamp}/{@code timestamptz} codecs only emit text bytes, so feeding them into the
   * binary array wire format makes the server misread each element), and every leaf value must be
   * binary-encodable by that element codec — a composite element, for instance, rejects a plain
   * {@link org.postgresql.util.PGobject}, which must bind as text. Callers that choose the bind
   * format (a Java array parameter, or {@link PgArray#toBytes()}) gate the binary path on this.
   *
   * @param value the array value (a Java array, a {@link PgArray}, or a JDBC {@link Array})
   * @param type the array type metadata
   * @param ctx the codec context
   * @return true if the array may be encoded in binary
   * @throws SQLException if type metadata cannot be resolved
   */
  @Override
  public boolean canEncodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    Object javaArray;
    if (value instanceof PgArray) {
      PgArray pgArray = (PgArray) value;
      if (pgArray.isBinary()) {
        return true;
      }
      javaArray = pgArray.getArray();
    } else if (value instanceof Array) {
      javaArray = ((Array) value).getArray();
    } else if (value.getClass().isArray()) {
      javaArray = value;
    } else {
      return false;
    }
    if (javaArray == null) {
      // A null backing array binds as an empty binary payload.
      return true;
    }
    int elementOid = type.getTypelem();
    if (elementOid == 0) {
      return false;
    }
    PgType elementType = ctx.getTypeInfo().getPgTypeByOid(elementOid);
    BinaryCodec elementCodec = ctx.getCodecs().getBinaryCodec(elementOid, elementType);
    if (elementCodec == null || !elementCodec.supportsBinaryEncoding()) {
      return false;
    }
    return leavesBinaryEncodable(javaArray, elementType, elementCodec, ctx);
  }

  /**
   * Recursively checks that every non-null leaf of a (possibly multi-dimensional) array is
   * binary-encodable by {@code elementCodec}. Reference arrays are walked level by level via their
   * {@code Object[]} view; a primitive leaf array (for example {@code double[]}) is always
   * binary-encodable, its element codec's support already verified by the caller.
   */
  private static boolean leavesBinaryEncodable(Object value, PgType elementType,
      BinaryCodec elementCodec, CodecContext ctx) throws SQLException {
    if (value instanceof Object[]) {
      for (Object element : (Object[]) value) {
        if (element != null
            && !leavesBinaryEncodable(element, elementType, elementCodec, ctx)) {
          return false;
        }
      }
      return true;
    }
    if (value.getClass().isArray()) {
      // A primitive leaf array (double[], int[], ...): value-independent binary support.
      return true;
    }
    return elementCodec.canEncodeBinary(value, elementType, ctx);
  }

  private static GenericArrayLeafCodec getGenericArrayLeafCodec(PgType arrayType, CodecContext ctx) throws SQLException {
    int elementOid = arrayType.getTypelem();
    PgType elementType = ctx.getTypeInfo().getPgTypeByOid(elementOid);
    Codec elementCodec = ctx.getCodecs().getByOid(elementOid, elementType);
    return new GenericArrayLeafCodec(elementType, elementCodec);
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
        MultiDimArrayText.encode(value, type.getDelimiter(), out, ctx,
            getGenericArrayLeafCodec(type, ctx));
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
      // getArray() shape. The temporal types decode as their java.sql form (see
      // leafContext); other types (PGobject for json/jsonb, ...) still take the
      // legacy path.
      Class<?> javaType = elementCodec.getDefaultJavaType();
      if (javaType == String.class
          || javaType == java.math.BigDecimal.class
          || javaType == java.util.UUID.class
          || javaType == byte[].class
          || javaType == java.sql.Date.class
          || javaType == java.sql.Time.class
          || javaType == java.sql.Timestamp.class) {
        return javaType;
      }
    }
    return null;
  }

  /**
   * The context to decode {@code componentType} leaves with. Temporal arrays decode to the
   * {@code java.sql} types regardless of the {@code getObject} java.time preferences, matching the
   * legacy array decoder (so {@code date[]} yields {@code Date[]}, never {@code LocalDate[]}); other
   * element types decode with the context unchanged.
   */
  private static CodecContext leafContext(Class<?> componentType, CodecContext ctx) {
    if (componentType == java.sql.Date.class
        || componentType == java.sql.Time.class
        || componentType == java.sql.Timestamp.class) {
      return ctx.withoutJavaTimePreferences();
    }
    return ctx;
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
    Class<?> componentType1 = componentType != null ? componentType : Object.class;
    return MultiDimArrayBinary.decode(data, componentType1, leafContext(componentType1, ctx),
        getGenericArrayLeafCodec(arrayType, ctx));
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
    Class<?> componentType1 = componentType != null ? componentType : Object.class;
    return MultiDimArrayText.decode(data, componentType1, arrayType.getDelimiter(),
        leafContext(componentType1, ctx), getGenericArrayLeafCodec(arrayType, ctx));
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
        PSQLState.DATA_TYPE_MISMATCH);
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
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    // Preserve the PostgreSQL text representation (e.g. {{1,0},{0,1}});
    // PgArray.toString() would re-emit elements with quotes.
    return data;
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw Codec.cannotDecode("array", "int");
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw Codec.cannotDecode("array", "int");
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw Codec.cannotDecode("array", "long");
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    throw Codec.cannotDecode("array", "long");
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw Codec.cannotDecode("array", "double");
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    throw Codec.cannotDecode("array", "double");
  }
}
