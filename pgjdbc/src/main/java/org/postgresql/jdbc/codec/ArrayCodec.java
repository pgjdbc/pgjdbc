/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.PgArray;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Codec for PostgreSQL array types.
 *
 * <p>Encoding walks the Java array and dispatches each leaf to the element type's codec via
 * {@link MultiDimArrayBinary} / {@link MultiDimArrayText} (using a primitive fast leaf when the
 * element type provides one, otherwise {@link GenericArrayLeafCodec}). For decoding it returns a
 * {@link PgArray} that lazily decodes elements on access through the shared walker (see
 * {@link #canDecodeArrayViaWalker}). It accepts both {@link Array} (PgArray) and raw Java array
 * objects for encoding.</p>
 */
public final class ArrayCodec implements StreamingBinaryCodec, StreamingTextCodec {

  public static final ArrayCodec INSTANCE = new ArrayCodec();

  private ArrayCodec() {
    // Singleton
  }

  // The array codec downcasts to PgCodecContext to branch on whether a connection backs a lazy
  // PgArray. Offline (connectionless) it decodes eagerly to a Java array instead; only an explicit
  // java.sql.Array/PgArray target, which needs connection-bound lazy ops (getResultSet), reports a
  // clear error via requireConnection. Child-type resolution and the leaf-context derivation go
  // through the CodecContext interface (slice 2c).
  private static PgCodecContext impl(CodecContext ctx) {
    return (PgCodecContext) ctx;
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
   * Validates a Java array whose SQL element type's Java representation is
   * {@code leafElementClass}, so an array-typed element (a {@code byte[]} for
   * {@code bytea}) is treated as a leaf rather than an inner dimension. This
   * lets {@code bytea[]} hold {@code byte[]} elements of differing lengths.
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
      throw Exceptions.cannotConvertToArray(javaArray);
    }
    MultiDimArraySupport.computeDimensionLengths(javaArray, dimensions);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] buf, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    // PgArray and the offline array decoder own a whole array payload; copy only for a genuine sub-slice.
    PgCodecContext impl = impl(ctx);
    if (impl.isConnectionBound()) {
      byte[] data = offset == 0 && length == buf.length ? buf : Arrays.copyOfRange(buf, offset, offset + length);
      // Lazy PgArray over the binary payload; elements decode on access through the connection.
      return new PgArray(impl.getConnection(), type.getOid(), data);
    }
    // Offline: no connection to back a lazy java.sql.Array, so decode eagerly to a Java array.
    return decodeBinaryArray(buf, offset, length, type, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof PgArray) {
      PgArray pgArray = (PgArray) value;
      if (pgArray.isBinary()) {
        byte[] bytes = pgArray.toBytes();
        if (bytes != null) {
          return bytes;
        }
      }
    }
    // Reuse streaming encoder
    BackpatchByteArrayOutputStream out = new BackpatchByteArrayOutputStream();
    try {
      encodeBinary(value, type, ctx, out);
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
  private static @Nullable ArrayLeafCodec fastLeafFor(TypeDescriptor arrayType, CodecContext ctx)
      throws SQLException {
    int elementOid = arrayType.getTypelem();
    if (elementOid == 0) {
      return null;
    }
    BinaryCodec elementCodec = ctx.resolveBinaryCodec(elementOid);
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
  public boolean canEncodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
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
    TypeDescriptor elementType = ctx.resolveType(elementOid);
    BinaryCodec elementCodec = ctx.resolveBinaryCodec(elementOid);
    if (elementCodec == null || !elementCodec.supportsBinaryEncoding()) {
      return false;
    }
    return leavesBinaryEncodable(javaArray, elementType, elementCodec, ctx);
  }

  /**
   * Recursively checks that every non-null leaf of a (possibly multi-dimensional) array is
   * binary-encodable by {@code elementCodec}. Reference arrays are walked level by level via their
   * {@code Object[]} view. A primitive leaf array (for example {@code int[]} bound to
   * {@code numeric[]}) is homogeneous, so its first boxed element decides: when the element codec
   * cannot binary-encode that boxed value (a type with no real binary codec, such as {@code money}
   * via the fallback codec), the array binds as text instead.
   */
  private static boolean leavesBinaryEncodable(Object value, TypeDescriptor elementType,
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
      // Primitive leaf array (int[], double[], ...): empty binds fine; otherwise the homogeneous
      // first boxed element is representative of the whole leaf.
      int len = java.lang.reflect.Array.getLength(value);
      return len == 0
          || elementCodec.canEncodeBinary(java.lang.reflect.Array.get(value, 0), elementType, ctx);
    }
    return elementCodec.canEncodeBinary(value, elementType, ctx);
  }

  private static GenericArrayLeafCodec getGenericArrayLeafCodec(TypeDescriptor arrayType, CodecContext ctx) throws SQLException {
    int elementOid = arrayType.getTypelem();
    TypeDescriptor elementType = ctx.resolveType(elementOid);
    Codec elementCodec = ctx.resolveCodec(elementOid);
    return new GenericArrayLeafCodec(elementType, elementCodec);
  }

  /**
   * Returns a leaf codec that decodes each element to {@code leafComponentType}, for a
   * {@code decodeXxxAs(T[].class)} call that asks for a specific reference element type the default
   * array decode would not produce — a {@code CustomDto[]} over a composite, a {@code LocalDate[]}
   * over {@code date}, a {@code String[]} over a non-string element. Returns {@code null} to keep the
   * existing fast/generic path when its component is already assignable to {@code leafComponentType}
   * ({@code Object[]}, {@code Integer[]}, {@code String[]} over {@code text}, ...) or the target is a
   * primitive (only a fast leaf decodes a primitive array). Each element is decoded through the
   * element codec's {@code decodeBinaryAs}/{@code decodeTextAs}, so no connection is required.
   */
  private static @Nullable GenericArrayLeafCodec typedElementLeaf(TypeDescriptor arrayType,
      Class<?> leafComponentType, @Nullable ArrayLeafCodec fastLeaf, CodecContext ctx)
      throws SQLException {
    if (leafComponentType == Object.class || leafComponentType.isPrimitive()) {
      return null;
    }
    TypeDescriptor elementType = ctx.resolveType(arrayType.getTypelem());
    Codec elementCodec = ctx.resolveCodec(arrayType.getTypelem());
    Class<?> defaultComponent;
    if (fastLeaf != null) {
      defaultComponent = fastLeaf.getBoxedComponentType();
    } else {
      Class<?> generic = genericComponentType(elementType, elementCodec);
      defaultComponent = generic != null ? generic : Object.class;
    }
    if (leafComponentType.isAssignableFrom(defaultComponent)) {
      return null;
    }
    return new GenericArrayLeafCodec(elementType, elementCodec, leafComponentType);
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PgCodecContext impl = impl(ctx);
    if (impl.isConnectionBound()) {
      // Lazy PgArray over the text literal; elements decode on access through the connection.
      return new PgArray(impl.getConnection(), type.getOid(), data);
    }
    // Offline: no connection to back a lazy java.sql.Array, so decode eagerly to a Java array.
    return decodeTextArray(data, type, ctx);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    StringBuilder sb = new StringBuilder();
    try {
      encodeText(value, type, ctx, sb);
    } catch (IOException e) {
      throw new AssertionError(e); // StringBuilder never throws
    }
    return sb.toString();
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    if (value instanceof PgArray) {
      PgArray pgArray = (PgArray) value;
      if (pgArray.isBinary()) {
        byte[] bytes = pgArray.toBytes();
        if (bytes != null) {
          out.write(bytes);
          return;
        }
      }
      // Text-mode PgArray: decode to Java array, then stream it as binary.
      Object javaArray = pgArray.getArray();
      if (javaArray != null) {
        encodeBinaryJavaArray(javaArray, type, ctx, out);
      }
      return;
    }
    if (value instanceof Array) {
      // Generic JDBC Array - get the underlying array and stream it.
      Object javaArray = ((Array) value).getArray();
      if (javaArray != null) {
        encodeBinaryJavaArray(javaArray, type, ctx, out);
      }
      return;
    }
    if (value.getClass().isArray()) {
      encodeBinaryJavaArray(value, type, ctx, out);
      return;
    }
    throw Exceptions.cannotConvertToArray(value);
  }

  /**
   * Writes the array body directly into {@code out} instead of materializing the whole array as a
   * {@code byte[]} first. The fast leaf and the generic per-element walker both dispatch through
   * {@link MultiDimArrayBinary}'s sink-based {@code encode}, so an element that is itself a
   * {@link org.postgresql.api.codec.StreamingBinaryCodec} (a nested array, a composite, ...) streams
   * straight into {@code out} without an intermediate per-element {@code byte[]}.
   */
  private static void encodeBinaryJavaArray(Object javaArray, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    ArrayLeafCodec fastLeaf = fastLeafFor(type, ctx);
    if (fastLeaf != null) {
      MultiDimArrayBinary.encode(javaArray, out, ctx, fastLeaf);
      return;
    }
    MultiDimArrayBinary.encode(javaArray, out, ctx, getGenericArrayLeafCodec(type, ctx));
  }

  @Override
  public void encodeText(Object value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    if (value instanceof PgArray) {
      // A PgArray already renders itself as a PostgreSQL array literal; emit it verbatim and
      // avoid a decode/re-encode round-trip.
      String str = value.toString();
      out.append(str != null ? str : "NULL");
      return;
    }
    Object javaArray;
    if (value instanceof Array) {
      // Foreign java.sql.Array: its toString() is not a PostgreSQL array literal, so unwrap the
      // backing array and render each leaf through the element codec via the shared walker,
      // mirroring encodeBinary.
      javaArray = ((Array) value).getArray();
      if (javaArray == null) {
        out.append("NULL");
        return;
      }
    } else if (value.getClass().isArray()) {
      javaArray = value;
    } else {
      throw Exceptions.cannotConvertToArray(value);
    }
    ArrayLeafCodec fastLeaf = fastLeafFor(type, ctx);
    if (fastLeaf != null) {
      MultiDimArrayText.encode(javaArray, type.getDelimiter(), out, ctx, fastLeaf);
      return;
    }
    // No primitive fast leaf: render every leaf through the element type's text codec via the
    // shared walker. Covers reference arrays (String[], UUID[], the temporal types,
    // BigDecimal[], composite, ...) and boxed primitive leaves (for example an int[] bound to
    // numeric[]).
    MultiDimArrayText.encode(javaArray, type.getDelimiter(), out, ctx,
        getGenericArrayLeafCodec(type, ctx));
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
   *       array;</li>
   *   <li>{@code bit}/{@code varbit} → {@code PGobject[]} (decoded by {@link BitCodec}, which parses
   *       the binary int4+packed form the legacy {@code Boolean[]} decoder could not);</li>
   *   <li>every other element type → {@code Object[]} of the scalar codec's value, the shape the
   *       legacy decoder produced via {@code MappedTypeObjectArrayDecoder} (xml, hstore, geometric,
   *       interval, domains — which report sqlType DISTINCT — and unknown user types).</li>
   * </ul>
   *
   * <p>Only returns {@code null} when no element codec is available, which does not happen for a
   * connection-bound context (the registry always resolves at least the fallback codec).</p>
   */
  private static @Nullable Class<?> genericComponentType(TypeDescriptor elementType,
      @Nullable Codec elementCodec) {
    if (elementType.isComposite() || elementType.getTyptype() == 'r') {
      return Object.class;
    }
    if (elementCodec != null) {
      // bit/varbit decode to PGobject for any width; produce a PGobject[] (the binary int4+packed
      // form is parsed by BitCodec, which the legacy Boolean[] decoder could not handle).
      if (elementCodec instanceof BitCodec) {
        return PGobject.class;
      }
      // Allowlist of element Java types whose generic decode matches the legacy
      // getArray() shape. The temporal types decode as their java.sql form (see
      // leafContext).
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
      // Every remaining non-fast-leaf element decodes into a generic Object[] holding its scalar
      // codec's value — the shape the legacy MappedTypeObjectArrayDecoder produced (xml, hstore,
      // geometric, interval, domains — which report sqlType DISTINCT — and unknown user types). The
      // fast-leaf and typed-allowlist branches above already cover every type the legacy decoder gave
      // a typed array, so reaching here means "not typed" and the result is always Object[].
      return Object.class;
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
  public static boolean canDecodeArrayViaWalker(TypeDescriptor arrayType, CodecContext ctx)
      throws SQLException {
    if (fastLeafFor(arrayType, ctx) != null) {
      return true;
    }
    int elementOid = arrayType.getTypelem();
    if (elementOid == 0) {
      return false;
    }
    TypeDescriptor elementType = ctx.resolveType(elementOid);
    Codec elementCodec = ctx.resolveCodec(elementOid);
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
   * @param offset start of this value's bytes within {@code data}
   * @param length number of bytes for this value
   * @param arrayType the array type metadata
   * @param ctx the codec context
   * @return the decoded array
   * @throws SQLException if decoding fails
   */
  public static Object decodeBinaryArray(byte[] data, int offset, int length,
      TypeDescriptor arrayType, CodecContext ctx) throws SQLException {
    ArrayLeafCodec fast = fastLeafFor(arrayType, ctx);
    if (fast != null) {
      return MultiDimArrayBinary.decode(data, offset, length, fast.getBoxedComponentType(), ctx, fast);
    }
    TypeDescriptor elementType = ctx.resolveType(arrayType.getTypelem());
    Codec elementCodec = ctx.resolveCodec(arrayType.getTypelem());
    Class<?> componentType = genericComponentType(elementType, elementCodec);
    Class<?> componentType1 = componentType != null ? componentType : Object.class;
    return MultiDimArrayBinary.decode(data, offset, length, componentType1,
        leafContext(componentType1, ctx), getGenericArrayLeafCodec(arrayType, ctx));
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
  public static Object decodeTextArray(String data, TypeDescriptor arrayType, CodecContext ctx)
      throws SQLException {
    ArrayLeafCodec fast = fastLeafFor(arrayType, ctx);
    if (fast != null) {
      return MultiDimArrayText.decode(data, fast.getBoxedComponentType(),
          arrayType.getDelimiter(), ctx, fast);
    }
    TypeDescriptor elementType = ctx.resolveType(arrayType.getTypelem());
    Codec elementCodec = ctx.resolveCodec(arrayType.getTypelem());
    Class<?> componentType = genericComponentType(elementType, elementCodec);
    Class<?> componentType1 = componentType != null ? componentType : Object.class;
    return MultiDimArrayText.decode(data, componentType1, arrayType.getDelimiter(),
        leafContext(componentType1, ctx), getGenericArrayLeafCodec(arrayType, ctx));
  }

  /** The outermost-dimension split of an array text literal: see {@link #splitTextArray}. */
  public static final class TextArrayElements {
    private final int dimensions;
    private final List<@Nullable String> elements;

    TextArrayElements(int dimensions, List<@Nullable String> elements) {
      this.dimensions = dimensions;
      this.elements = elements;
    }

    /** Number of array dimensions (1 for {@code {1,2}}, 2 for {@code {{1,2}}}). */
    public int dimensions() {
      return dimensions;
    }

    /**
     * The outermost-dimension elements, each kept as its raw text: a leaf value for a
     * one-dimensional array (or {@code null} for an unquoted {@code NULL}), or the nested
     * {@code {...}} literal for a higher-dimensional array.
     */
    public List<@Nullable String> elements() {
      return elements;
    }
  }

  /**
   * Splits an array text literal into its outermost-dimension elements without decoding them, using
   * the shared {@link LiteralCursor} tokenizer. Each returned element keeps its raw text (the
   * unquoted leaf value, or the nested literal for a multi-dimensional array), so a consumer can
   * decode it through the element type's codec — this is what {@link PgArray#getResultSet()} needs,
   * and it avoids a decode/re-encode round-trip that would, for example, lose a {@code money}
   * currency symbol.
   *
   * @param literal the array text literal (for example {@code {1,2,3}})
   * @param delimiter the element delimiter for this array type
   * @return the dimension count and the raw outermost-dimension elements
   * @throws SQLException if the literal is malformed
   */
  public static TextArrayElements splitTextArray(String literal, char delimiter) throws SQLException {
    LiteralCursor cur = LiteralCursor.over(literal);
    cur.skipDimensionPrefix();
    int dimensions = cur.countLeadingBraces();
    List<@Nullable String> elements = new ArrayList<>();
    cur.expect('{');
    if (!cur.tryConsume('}')) {
      boolean multiDim = dimensions > 1;
      do {
        if (multiDim) {
          elements.add(cur.captureSubarray());
        } else {
          cur.readValue(delimiter, '}');
          if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
            elements.add(null);
          } else {
            elements.add(new String(cur.tokenChars(), cur.tokenOffset(), cur.tokenLength()));
          }
        }
      } while (cur.tryConsume(delimiter));
      cur.expect('}');
    }
    return new TextArrayElements(dimensions, elements);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] buf, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass == Object.class) {
      // Connection-bound: a lazy PgArray; offline: an eagerly decoded Java array. Either way,
      // decodeBinary already reads buf as a [offset, offset + length) slice without copying.
      return (T) decodeBinary(buf, offset, length, type, ctx);
    }
    if (targetClass == Array.class || targetClass == PgArray.class) {
      // A java.sql.Array is connection-bound (lazy getResultSet); offline cannot back one, so this
      // reports a clear error rather than silently handing back a Java array of a different type.
      // PgArray retains the byte[] beyond this call, so — unlike the walker paths below, which only
      // read the slice for the duration of this call — it needs its own copy whenever buf is a larger
      // shared buffer that may be reused or overwritten afterwards.
      byte[] data = offset == 0 && length == buf.length ? buf : Arrays.copyOfRange(buf, offset, offset + length);
      return (T) new PgArray(impl(ctx).requireConnection(type), type.getOid(), data);
    }
    if (targetClass.isArray()) {
      Class<?> leafComponentType = MultiDimArraySupport.leafComponentType(targetClass);
      ArrayLeafCodec fastLeaf = fastLeafFor(type, ctx);
      if (fastLeaf != null && fastLeaf.supportsTargetComponent(leafComponentType)) {
        return (T) MultiDimArrayBinary.decode(buf, offset, length, leafComponentType, ctx, fastLeaf);
      }
      GenericArrayLeafCodec typedLeaf = typedElementLeaf(type, leafComponentType, fastLeaf, ctx);
      if (typedLeaf != null) {
        // Decode each element to the requested component type (e.g. CustomDto[] over a composite).
        return (T) MultiDimArrayBinary.decode(buf, offset, length, leafComponentType, ctx, typedLeaf);
      }
      // Element type has no matching fast leaf: decode through the shared codec walker, which
      // yields the same component type the legacy decoder produced (typed array, String[], or
      // Object[] for composite/range), so getObject(col, T[].class) is unchanged.
      return (T) decodeBinaryArray(buf, offset, length, type, ctx);
    }
    throw Exceptions.cannotConvertArrayTo(targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Object.class) {
      // Connection-bound: a lazy PgArray; offline: an eagerly decoded Java array.
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass == Array.class || targetClass == PgArray.class) {
      // A java.sql.Array is connection-bound (lazy getResultSet); offline cannot back one.
      return (T) new PgArray(impl(ctx).requireConnection(type), type.getOid(), data);
    }
    if (targetClass.isArray()) {
      Class<?> leafComponentType = MultiDimArraySupport.leafComponentType(targetClass);
      ArrayLeafCodec fastLeaf = fastLeafFor(type, ctx);
      if (fastLeaf != null && fastLeaf.supportsTargetComponent(leafComponentType)) {
        return (T) MultiDimArrayText.decode(data, leafComponentType, type.getDelimiter(), ctx,
            fastLeaf);
      }
      GenericArrayLeafCodec typedLeaf = typedElementLeaf(type, leafComponentType, fastLeaf, ctx);
      if (typedLeaf != null) {
        // Decode each element to the requested component type (e.g. CustomDto[] over a composite).
        return (T) MultiDimArrayText.decode(data, leafComponentType, type.getDelimiter(), ctx,
            typedLeaf);
      }
      // Element type has no matching fast leaf: decode through the shared codec walker.
      return (T) decodeTextArray(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    throw Exceptions.cannotConvertArrayTo(targetClass.getName());
  }

  @Override
  public @Nullable String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // Preserve the PostgreSQL text representation (e.g. {{1,0},{0,1}});
    // PgArray.toString() would re-emit elements with quotes.
    return data;
  }

}
