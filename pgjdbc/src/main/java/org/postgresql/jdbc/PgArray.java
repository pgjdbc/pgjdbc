/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.Field;
import org.postgresql.core.Oid;
import org.postgresql.core.Tuple;
import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.codec.ArrayCodec;
import org.postgresql.jdbc.codec.CompositeCodec;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Array is used collect one column of query result data.
 *
 * <p>Read a field of type Array into either a natively-typed Java array object or a ResultSet.
 * Accessor methods provide the ability to capture array slices.</p>
 *
 * <p>Other than the constructor all methods are direct implementations of those specified for
 * java.sql.Array. Please refer to the javadoc for java.sql.Array for detailed descriptions of the
 * functionality and parameters of the methods of this class.</p>
 *
 * @see ResultSet#getArray
 */
public class PgArray implements Array {

  /**
   * A database connection.
   */
  protected @Nullable BaseConnection connection;

  /**
   * The OID of this field.
   */
  private final int oid;

  /**
   * Snapshot of PgCodecContext at array creation time.
   * This ensures consistent type mappings during array operations.
   */
  private final PgCodecContext codecContext;

  /**
   * Field value as String.
   */
  protected @Nullable String fieldString;

  protected byte @Nullable [] fieldBytes;

  /**
   * Original Java array supplied via {@link PgConnection#createArrayOf}.
   * It is serialized lazily when the value is bound to a statement.
   */
  protected @Nullable Object fieldArray;

  private PgArray(BaseConnection connection, int oid) throws SQLException {
    this.connection = connection;
    this.oid = oid;
    this.codecContext = connection.getCodecContext();
  }

  /**
   * Create a new Array.
   *
   * @param connection a database connection
   * @param oid the oid of the array datatype
   * @param fieldString the array data in string form
   * @throws SQLException if something wrong happens
   */
  public PgArray(BaseConnection connection, int oid, @Nullable String fieldString)
      throws SQLException {
    this(connection, oid);
    this.fieldString = fieldString;
  }

  /**
   * Create a new Array.
   *
   * @param connection a database connection
   * @param oid the oid of the array datatype
   * @param fieldBytes the array data in byte form
   * @throws SQLException if something wrong happens
   */
  public PgArray(BaseConnection connection, int oid, byte @Nullable [] fieldBytes)
      throws SQLException {
    this(connection, oid);
    this.fieldBytes = fieldBytes;
  }

  /**
   * Create a new Array backed by a Java array.
   *
   * @param connection a database connection
   * @param oid the oid of the array datatype
   * @param fieldArray Java array value
   * @throws SQLException if something wrong happens
   */
  public PgArray(BaseConnection connection, int oid, Object fieldArray)
      throws SQLException {
    this(connection, oid);
    this.fieldArray = fieldArray;
  }

  /**
   * Returns {@code oid} of the array type
   * @return array type oid
   */
  public int getOid() {
    return oid;
  }

  private BaseConnection getConnection() {
    return castNonNull(connection);
  }

  /**
   * Resolves type metadata through the codec context captured at construction, so type resolution
   * does not depend on the live connection. The connection is reserved for the operations that need
   * it — materializing a {@link ResultSet} and reading the query mode.
   */
  private TypeInfo typeInfo() {
    return codecContext.getTypeInfo();
  }

  /**
   * Rejects use after {@link #free()}, as the {@code java.sql.Array} contract requires. The
   * connection is the freed marker: every constructor sets it and only {@link #free()} clears it.
   */
  private void checkFreed() throws SQLException {
    if (connection == null) {
      throw new PSQLException(GT.tr("free() was called on this Array previously"),
          PSQLState.OBJECT_NOT_IN_STATE);
    }
  }

  private PgType getPgType() throws SQLException {
    return typeInfo().getPgTypeByOid(oid);
  }

  private PgType getElementPgType() throws SQLException {
    return typeInfo().getPgTypeByOid(getPgType().getTypelem());
  }

  @Override
  @SuppressWarnings("return")
  public Object getArray() throws SQLException {
    return getArrayImpl(1, 0, null);
  }

  @Override
  @SuppressWarnings("return")
  public Object getArray(long index, int count) throws SQLException {
    return getArrayImpl(index, count, null);
  }

  @SuppressWarnings("return")
  public Object getArrayImpl(Map<String, Class<?>> map) throws SQLException {
    return getArrayImpl(1, 0, map);
  }

  @Override
  @SuppressWarnings("return")
  public Object getArray(Map<String, Class<?>> map) throws SQLException {
    return getArrayImpl(map);
  }

  @Override
  @SuppressWarnings("return")
  public Object getArray(long index, int count, @Nullable Map<String, Class<?>> map)
      throws SQLException {
    return getArrayImpl(index, count, map);
  }

  public @Nullable Object getArrayImpl(long index, int count, @Nullable Map<String, Class<?>> map)
      throws SQLException {
    checkFreed();
    // No depth guard here: nesting is bounded inside the codec implementations (CompositeCodec,
    // GenericArrayLeafCodec, DomainCodec, ...). A nested array field decodes to a lazy PgArray in a
    // connection-bound context, so a single getArrayImpl only unwraps one array level; deeper
    // recursion runs through those guarded codecs.
    // array index is out of range
    if (index < 1) {
      throw new PSQLException(GT.tr("The array index is out of range: {0}", index),
          PSQLState.DATA_ERROR);
    }
    if (map != null && !map.isEmpty()) {
      map = IdentifierNormalizingTypeMap.of(map, codecContext.getTypeInfo());
    }

    boolean useTextRepresentation = false;
    Object javaArray = fieldArray;
    if (javaArray != null) {
      // Return the backing array verbatim only when its leaf component is a
      // reference type: getArray() must hand back a boxed array (Double[][],
      // never double[][]), so a primitive-backed array falls through to the
      // encode/decode round-trip below, which boxes the leaves.
      if ((map == null || map.isEmpty()) && index == 1 && count == 0
          && !hasPrimitiveLeaf(javaArray)) {
        return javaArray;
      }
      fieldString = ArrayCodec.INSTANCE.encodeText(javaArray, getPgType(), codecContext);
      useTextRepresentation = true;
    }

    // Decode the whole array through the shared codec walker (a primitive fast leaf yields a typed
    // array such as Integer[]/Long[]; every other element type an Object[]), then take the
    // index/count slice and apply any per-call type map (a no-op unless an element is a PGobject
    // mapped to an SQLData class; this also makes a non-empty map work in binary). The connection
    // is always present (every PgArray is built from one) and canDecodeArrayViaWalker only fails
    // for the degenerate element-oid-0 case, so a null result here means a null/empty array, for
    // which getArray() returns null.
    Object full = null;
    if (ArrayCodec.canDecodeArrayViaWalker(getPgType(), codecContext)) {
      byte[] fieldBytes = this.fieldBytes;
      if (fieldBytes != null && !useTextRepresentation) {
        TypeDescriptor arrayType = getPgType();
        full = ArrayCodec.decodeBinaryArray(fieldBytes, 0, fieldBytes.length, arrayType, codecContext);
      } else if (fieldString != null) {
        full = ArrayCodec.decodeTextArray(fieldString, getPgType(), codecContext);
      }
    }
    if (full == null) {
      return null;
    }
    Object result = sliceArray(full, index, count);
    if (map != null && !map.isEmpty() && result instanceof Object[]) {
      applyTypeMapping((Object[]) result, map);
    }
    return result;
  }

  /**
   * Returns the whole codec-decoded array for {@code index == 1 && count == 0}, otherwise a copy of
   * the outermost-dimension range {@code [index, index + count)} (1-based). Matching the legacy
   * decoder, {@code count == 0} requests the full size, so any {@code index > 1} is out of range. The
   * component type is preserved, so a {@code String[]} slice stays a {@code String[]}.
   */
  private static Object sliceArray(Object full, long index, int count) throws SQLException {
    int len = java.lang.reflect.Array.getLength(full);
    if (index == 1 && count == 0) {
      return full;
    }
    int from = (int) (index - 1);
    int n = count == 0 ? len : count;
    if (from < 0 || n < 0 || from + n > len) {
      throw new PSQLException(
          GT.tr("The array index is out of range: {0}, number of elements: {1}.",
              index + count, (long) len),
          PSQLState.DATA_ERROR);
    }
    Class<?> componentType = castNonNull(full.getClass().getComponentType(), "array component type");
    Object out = java.lang.reflect.Array.newInstance(componentType, n);
    System.arraycopy(full, from, out, 0, n);
    return out;
  }

  /**
   * Applies type mapping to convert PGobject instances to SQLData implementations.
   */
  @SuppressWarnings("unchecked")
  private void applyTypeMapping(Object @Nullable [] array, Map<String, Class<?>> map)
      throws SQLException {
    if (array == null) {
      return;
    }
    PgCodecContext ctx = codecContext.withTypeMap(map);
    for (int i = 0; i < array.length; i++) {
      Object element = array[i];
      if (element instanceof PGobject) {
        PGobject pgObj = (PGobject) element;
        Class<?> targetClass = map.get(pgObj.getType());
        if (targetClass != null && SQLData.class.isAssignableFrom(targetClass)) {
          String value = pgObj.getValue();
          if (value != null) {
            int elementOid = getPgType().getTypelem();
            PgType elementType = typeInfo().getPgTypeByOid(elementOid);
            Object decoded = CompositeCodec.INSTANCE.decodeTextAs(
                value, elementType, (Class<? extends SQLData>) targetClass, ctx);
            if (decoded != null) {
              array[i] = decoded;
            }
          }
        }
      } else if (element instanceof Object[]) {
        // Recursively apply to nested arrays
        applyTypeMapping((Object[]) element, map);
      }
    }
  }

  private ResultSet readBinaryResultSet(byte[] fieldBytes, int index, int count)
      throws SQLException {
    int dimensions = ByteConverter.int4(fieldBytes, 0);
    // int flags = ByteConverter.int4(fieldBytes, 4); // bit 0: 0=no-nulls, 1=has-nulls
    int elementOid = ByteConverter.int4(fieldBytes, 8);
    int pos = 12;
    int[] dims = new int[dimensions];
    for (int d = 0; d < dimensions; d++) {
      dims[d] = ByteConverter.int4(fieldBytes, pos);
      pos += 4;
      /* int lbound = ByteConverter.int4(fieldBytes, pos); */
      pos += 4;
    }
    if (count > 0 && dimensions > 0) {
      dims[0] = Math.min(count, dims[0]);
    }
    List<Tuple> rows = new ArrayList<>();
    Field[] fields = new Field[2];

    storeValues(fieldBytes, rows, fields, elementOid, dims, pos, 0, index);

    BaseStatement stat = (BaseStatement) getConnection()
        .createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    return stat.createDriverResultSet(fields, rows);
  }

  private int storeValues(byte[] fieldBytes, List<Tuple> rows, Field[] fields, int elementOid,
      final int[] dims,
      int pos, final int thisDimension, int index) throws SQLException {
    // handle an empty array
    if (dims.length == 0) {
      fields[0] = new Field("INDEX", Oid.INT4);
      fields[0].setFormat(Field.BINARY_FORMAT);
      fields[1] = new Field("VALUE", elementOid);
      fields[1].setFormat(Field.BINARY_FORMAT);
      for (int i = 1; i < index; i++) {
        int len = ByteConverter.int4(fieldBytes, pos);
        pos += 4;
        if (len != -1) {
          pos += len;
        }
      }
    } else if (thisDimension == dims.length - 1) {
      fields[0] = new Field("INDEX", Oid.INT4);
      fields[0].setFormat(Field.BINARY_FORMAT);
      fields[1] = new Field("VALUE", elementOid);
      fields[1].setFormat(Field.BINARY_FORMAT);
      for (int i = 1; i < index; i++) {
        int len = ByteConverter.int4(fieldBytes, pos);
        pos += 4;
        if (len != -1) {
          pos += len;
        }
      }
      for (int i = 0; i < dims[thisDimension]; i++) {
        byte[][] rowData = new byte[2][];
        rowData[0] = new byte[4];
        ByteConverter.int4(rowData[0], 0, i + index);
        rows.add(new Tuple(rowData));
        int len = ByteConverter.int4(fieldBytes, pos);
        pos += 4;
        if (len == -1) {
          continue;
        }
        rowData[1] = new byte[len];
        System.arraycopy(fieldBytes, pos, rowData[1], 0, rowData[1].length);
        pos += len;
      }
    } else {
      fields[0] = new Field("INDEX", Oid.INT4);
      fields[0].setFormat(Field.BINARY_FORMAT);
      fields[1] = new Field("VALUE", oid);
      fields[1].setFormat(Field.BINARY_FORMAT);
      int nextDimension = thisDimension + 1;
      int dimensionsLeft = dims.length - nextDimension;
      for (int i = 1; i < index; i++) {
        pos = calcRemainingDataLength(fieldBytes, dims, pos, elementOid, nextDimension);
      }
      for (int i = 0; i < dims[thisDimension]; i++) {
        byte[][] rowData = new byte[2][];
        rowData[0] = new byte[4];
        ByteConverter.int4(rowData[0], 0, i + index);
        rows.add(new Tuple(rowData));
        int dataEndPos = calcRemainingDataLength(fieldBytes, dims, pos, elementOid, nextDimension);
        int dataLength = dataEndPos - pos;
        rowData[1] = new byte[12 + 8 * dimensionsLeft + dataLength];
        ByteConverter.int4(rowData[1], 0, dimensionsLeft);
        System.arraycopy(fieldBytes, 4, rowData[1], 4, 8);
        System.arraycopy(fieldBytes, 12 + nextDimension * 8, rowData[1], 12, dimensionsLeft * 8);
        System.arraycopy(fieldBytes, pos, rowData[1], 12 + dimensionsLeft * 8, dataLength);
        pos = dataEndPos;
      }
    }
    return pos;
  }

  private static int calcRemainingDataLength(byte[] fieldBytes,
      int[] dims, int pos, int elementOid, int thisDimension) {
    if (thisDimension == dims.length - 1) {
      for (int i = 0; i < dims[thisDimension]; i++) {
        int len = ByteConverter.int4(fieldBytes, pos);
        pos += 4;
        if (len == -1) {
          continue;
        }
        pos += len;
      }
    } else {
      for (int i = 0; i < dims[thisDimension]; i++) {
        pos = calcRemainingDataLength(fieldBytes, dims, pos, elementOid, thisDimension + 1);
      }
    }
    return pos;
  }

  @Override
  public int getBaseType() throws SQLException {
    checkFreed();
    return getElementPgType().getSqlType();
  }

  @Override
  public String getBaseTypeName() throws SQLException {
    checkFreed();
    // Legacy contract: raw pg_type.typname (e.g. "int4") for types reachable
    // via the search_path, but a fully qualified \"schema\".\"typname\" form
    // for off-path or quoted types (e.g. "Composites"."ComplexCompositeTest").
    int elemOid = getElementPgType().getOid();
    TypeInfo typeInfo = typeInfo();
    if (typeInfo instanceof TypeInfoCache) {
      String displayName = ((TypeInfoCache) typeInfo).getPGTypeDisplayName(elemOid);
      if (displayName != null) {
        return displayName;
      }
    }
    return getElementPgType().getTypeName().getName();
  }

  @Override
  public ResultSet getResultSet() throws SQLException {
    return getResultSetImpl(1, 0, null);
  }

  @Override
  public ResultSet getResultSet(long index, int count) throws SQLException {
    return getResultSetImpl(index, count, null);
  }

  @Override
  public ResultSet getResultSet(@Nullable Map<String, Class<?>> map) throws SQLException {
    return getResultSetImpl(map);
  }

  @Override
  public ResultSet getResultSet(long index, int count, @Nullable Map<String, Class<?>> map)
      throws SQLException {
    return getResultSetImpl(index, count, map);
  }

  public ResultSet getResultSetImpl(@Nullable Map<String, Class<?>> map) throws SQLException {
    return getResultSetImpl(1, 0, map);
  }

  public ResultSet getResultSetImpl(long index, int count, @Nullable Map<String, Class<?>> map)
      throws SQLException {
    checkFreed();
    // No depth guard here: this only tokenizes the array into raw per-element text/bytes; the
    // elements are decoded lazily by the returned ResultSet through the guarded codec path.
    if (map != null && !map.isEmpty()) {
      map = IdentifierNormalizingTypeMap.of(map, codecContext.getTypeInfo());
    }

    // array index is out of range
    if (index < 1) {
      throw new PSQLException(GT.tr("The array index is out of range: {0}", index),
          PSQLState.DATA_ERROR);
    }

    if (fieldBytes != null) {
      return applyResultSetTypeMap(readBinaryResultSet(fieldBytes, (int) index, count), map);
    }

    Object array = fieldArray;
    if (fieldString == null && array != null) {
      fieldString = ArrayCodec.INSTANCE.encodeText(array, getPgType(), codecContext);
    }

    // Split the literal into its outermost-dimension elements through the codec's tokenizer
    // (raw text per element, so a per-element re-decode by the consumer is faithful — e.g. a
    // money currency symbol is preserved). Each element is a leaf value (1-D) or a nested literal.
    ArrayCodec.TextArrayElements split =
        ArrayCodec.splitTextArray(castNonNull(fieldString), getPgType().getDelimiter());
    List<@Nullable String> elements = split.elements();
    int size = elements.size();

    if (count == 0) {
      count = size;
    }

    // array index out of range
    if ((--index) + count > size) {
      throw new PSQLException(
          GT.tr("The array index is out of range: {0}, number of elements: {1}.",
                  index + count, (long) size),
          PSQLState.DATA_ERROR);
    }

    List<Tuple> rows = new ArrayList<>();

    Field[] fields = new Field[2];
    fields[0] = new Field("INDEX", Oid.INT4);
    // 1-D: the VALUE column is the element type; higher dimensions expose each row as a sub-array.
    fields[1] = new Field("VALUE", split.dimensions() <= 1 ? getPgType().getTypelem() : oid);

    for (int i = 0; i < count; i++) {
      int offset = (int) index + i;
      String v = elements.get(offset);
      byte[] @Nullable [] t = new byte[2][0];
      t[0] = getConnection().encodeString(Integer.toString(offset + 1));
      t[1] = v == null ? null : getConnection().encodeString(v);
      rows.add(new Tuple(t));
    }

    BaseStatement stat = (BaseStatement) getConnection()
        .createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    return applyResultSetTypeMap(stat.createDriverResultSet(fields, rows), map);
  }

  /**
   * Threads a non-empty per-call type map into {@code rs} so that {@code getObject} on the array's
   * element ResultSet honors the caller's {@code SQLData} mapping (a no-op for a built-in element
   * type). The map is a no-op until a row's element type matches a key, so a non-composite array is
   * unaffected.
   */
  private static ResultSet applyResultSetTypeMap(ResultSet rs,
      @Nullable Map<String, Class<?>> map) throws SQLException {
    if (map != null && !map.isEmpty() && rs instanceof PgResultSet) {
      ((PgResultSet) rs).setTypeMapOverride(map);
    }
    return rs;
  }

  @Override
  @SuppressWarnings("nullness")
  public @Nullable String toString() {
    Object javaArray = fieldArray;
    if (fieldString == null && javaArray != null) {
      try {
        fieldString = ArrayCodec.INSTANCE.encodeText(javaArray, getPgType(), codecContext);
      } catch (SQLException e) {
        fieldString = "NULL"; // punt
      }
    }
    if (fieldString == null && fieldBytes != null) {
      try {
        TypeDescriptor arrayType = getPgType();
        Object array = ArrayCodec.decodeBinaryArray(fieldBytes, 0, fieldBytes.length, arrayType, codecContext);
        fieldString = ArrayCodec.INSTANCE.encodeText(array, getPgType(), codecContext);
      } catch (SQLException e) {
        fieldString = "NULL"; // punt
      }
    }
    return fieldString;
  }

  public static void escapeArrayElement(StringBuilder b, String s) {
    b.append('"');
    for (int j = 0; j < s.length(); j++) {
      char c = s.charAt(j);
      if (c == '"' || c == '\\') {
        b.append('\\');
      }

      b.append(c);
    }
    b.append('"');
  }

  public boolean isBinary() {
    return fieldBytes != null;
  }

  /**
   * Whether the leaf (innermost) component type of {@code array} is a Java
   * primitive, for example {@code double[][]} or {@code int[]}. Such an array
   * must be boxed before being returned from {@link #getArray()}.
   */
  private static boolean hasPrimitiveLeaf(Object array) {
    Class<?> c = array.getClass();
    while (c.isArray()) {
      c = castNonNull(c.getComponentType());
    }
    return c.isPrimitive();
  }

  public byte @Nullable [] toBytes() throws SQLException {
    Object array = fieldArray;
    if (fieldBytes == null && array != null
        && getConnection().getPreferQueryMode() != PreferQueryMode.SIMPLE
        && ArrayCodec.INSTANCE.canEncodeBinary(array, getPgType(), codecContext)) {
      fieldBytes = ArrayCodec.INSTANCE.encodeBinary(array, getPgType(), codecContext);
    }
    return fieldBytes;
  }

  @Override
  public void free() throws SQLException {
    connection = null;
    fieldString = null;
    fieldBytes = null;
    fieldArray = null;
  }
}
