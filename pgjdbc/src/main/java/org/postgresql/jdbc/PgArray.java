/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.Driver;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.Field;
import org.postgresql.core.Oid;
import org.postgresql.core.Tuple;
import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.ArrayDecoding.PgArrayList;
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
   * Snapshot of CodecContext at array creation time.
   * This ensures consistent type mappings during array operations.
   */
  private final CodecContext codecContext;

  /**
   * Field value as String.
   */
  protected @Nullable String fieldString;

  /**
   * Value of field as {@link PgArrayList}. Will be initialized only once within
   * {@link #buildArrayList(String)}.
   */
  protected ArrayDecoding.@Nullable PgArrayList arrayList;

  protected byte @Nullable [] fieldBytes;

  /**
   * Original Java array supplied via {@link PgConnection#createArrayOf}.
   * It is serialized lazily when the value is bound to a statement.
   */
  protected @Nullable Object fieldArray;

  private final ResourceLock lock = new ResourceLock();

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

  private PgType getPgType() throws SQLException {
    TypeInfo typeInfo = getConnection().getTypeInfo();
    return typeInfo.getPgTypeByOid(oid);
  }

  private PgType getElementPgType() throws SQLException {
    TypeInfo typeInfo = getConnection().getTypeInfo();
    return typeInfo.getPgTypeByOid(getPgType().getTypelem());
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
    CodecDepth.enter();
    try {
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
        if ((map == null || map.isEmpty()) && index == 1 && count == 0) {
          return javaArray;
        }
        fieldString = ArrayCodec.INSTANCE.encodeText(javaArray, getPgType(), codecContext);
        useTextRepresentation = true;
      }

      // Decode through the shared codec walker when the element type supports it:
      // a primitive fast leaf yields a typed array (Integer[]/Long[]/...), and a
      // composite/range element decodes generically into Object[] with per-element
      // slice dispatch. Either way this avoids the legacy per-element String/byte[]
      // copy. Slicing and type-map requests still take the legacy path below.
      if (codecContext.isConnectionBound()
          && (map == null || map.isEmpty()) && index == 1 && count == 0
          && ArrayCodec.canDecodeArrayViaWalker(getPgType(), codecContext)) {
        if (fieldBytes != null && !useTextRepresentation) {
          return ArrayCodec.decodeBinaryArray(fieldBytes, getPgType(), codecContext);
        }
        if (fieldString != null) {
          return ArrayCodec.decodeTextArray(fieldString, getPgType(), codecContext);
        }
      }

      if (fieldBytes != null && !useTextRepresentation) {
        // Binary format - maps not supported for binary arrays yet
        if (map != null && !map.isEmpty()) {
          throw Driver.notImplemented(this.getClass(), "getArrayImpl(long,int,Map) for binary arrays");
        }
        return readBinaryArray(fieldBytes, (int) index, count);
      }

      if (fieldString == null) {
        return null;
      }

      final PgArrayList arrayList = buildArrayList(fieldString);

      if (count == 0) {
        count = arrayList.size();
      }

      // array index out of range
      if ((index - 1) + count > arrayList.size()) {
        throw new PSQLException(
            GT.tr("The array index is out of range: {0}, number of elements: {1}.",
                index + count, (long) arrayList.size()),
            PSQLState.DATA_ERROR);
      }

      Object array = buildArray(arrayList, (int) index, count);

      // Apply type mapping for SQLData implementations
      if (map != null && !map.isEmpty() && array instanceof Object[]) {
        applyTypeMapping((Object[]) array, map);
      }

      return array;
    } finally {
      CodecDepth.exit();
    }
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
    CodecContext ctx = codecContext.withTypeMap(map);
    for (int i = 0; i < array.length; i++) {
      Object element = array[i];
      if (element instanceof PGobject) {
        PGobject pgObj = (PGobject) element;
        Class<?> targetClass = map.get(pgObj.getType());
        if (targetClass != null && SQLData.class.isAssignableFrom(targetClass)) {
          String value = pgObj.getValue();
          if (value != null) {
            int elementOid = getPgType().getTypelem();
            PgType elementType = castNonNull(connection).getTypeInfo().getPgTypeByOid(elementOid);
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

  private Object readBinaryArray(byte[] fieldBytes, int index, int count) throws SQLException {
    return ArrayDecoding.readBinaryArray(index, count, fieldBytes, getConnection());
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

  /**
   * Build {@link ArrayList} from field's string input. As a result of this method
   * {@link #arrayList} is build. Method can be called many times in order to make sure that array
   * list is ready to use, however {@link #arrayList} will be set only once during first call.
   */
  private PgArrayList buildArrayList(String fieldString) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (arrayList == null) {
        arrayList = ArrayDecoding.buildArrayList(fieldString, getPgType().getDelimiter());
      }
      return arrayList;
    }
  }

  /**
   * Convert {@link ArrayList} to array.
   *
   * @param input list to be converted into array
   */
  private Object buildArray(ArrayDecoding.PgArrayList input, int index, int count) throws SQLException {
    final BaseConnection connection = getConnection();
    return ArrayDecoding.readStringArray(index, count, getPgType().getTypelem(), input, connection);
  }

  @Override
  public int getBaseType() throws SQLException {
    return getElementPgType().getSqlType();
  }

  @Override
  public String getBaseTypeName() throws SQLException {
    // Legacy contract: raw pg_type.typname (e.g. "int4") for types reachable
    // via the search_path, but a fully qualified \"schema\".\"typname\" form
    // for off-path or quoted types (e.g. "Composites"."ComplexCompositeTest").
    int elemOid = getElementPgType().getOid();
    TypeInfo typeInfo = getConnection().getTypeInfo();
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
    CodecDepth.enter();
    try {
      // for now maps aren't supported.
      if (map != null && !map.isEmpty()) {
        throw Driver.notImplemented(this.getClass(), "getResultSetImpl(long,int,Map)");
      }

      // array index is out of range
      if (index < 1) {
        throw new PSQLException(GT.tr("The array index is out of range: {0}", index),
            PSQLState.DATA_ERROR);
      }

      if (fieldBytes != null) {
        return readBinaryResultSet(fieldBytes, (int) index, count);
      }

      Object array = fieldArray;
      if (fieldString == null && array != null) {
        fieldString = ArrayCodec.INSTANCE.encodeText(array, getPgType(), codecContext);
      }

      final PgArrayList arrayList = buildArrayList(castNonNull(fieldString));

      if (count == 0) {
        count = arrayList.size();
      }

      // array index out of range
      if ((--index) + count > arrayList.size()) {
        throw new PSQLException(
            GT.tr("The array index is out of range: {0}, number of elements: {1}.",
                    index + count, (long) arrayList.size()),
            PSQLState.DATA_ERROR);
      }

      List<Tuple> rows = new ArrayList<>();

      Field[] fields = new Field[2];

      // one dimensional array
      if (arrayList.dimensionsCount <= 1) {
        // array element type
        final int baseOid = getPgType().getTypelem();
        fields[0] = new Field("INDEX", Oid.INT4);
        fields[1] = new Field("VALUE", baseOid);

        for (int i = 0; i < count; i++) {
          int offset = (int) index + i;
          byte[] @Nullable [] t = new byte[2][0];
          String v = (String) arrayList.get(offset);
          t[0] = getConnection().encodeString(Integer.toString(offset + 1));
          t[1] = v == null ? null : getConnection().encodeString(v);
          rows.add(new Tuple(t));
        }
      } else {
        // when multi-dimensional
        fields[0] = new Field("INDEX", Oid.INT4);
        fields[1] = new Field("VALUE", oid);
        for (int i = 0; i < count; i++) {
          int offset = (int) index + i;
          byte[] @Nullable [] t = new byte[2][0];
          Object v = arrayList.get(offset);

          t[0] = getConnection().encodeString(Integer.toString(offset + 1));
          t[1] = v == null ? null : getConnection().encodeString(toString((ArrayDecoding.PgArrayList) v));
          rows.add(new Tuple(t));
        }
      }

      BaseStatement stat = (BaseStatement) getConnection()
          .createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
      return stat.createDriverResultSet(fields, rows);
    } finally {
      CodecDepth.exit();
    }
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
        Object array = readBinaryArray(fieldBytes, 1, 0);
        fieldString = ArrayCodec.INSTANCE.encodeText(array, getPgType(), codecContext);
      } catch (SQLException e) {
        fieldString = "NULL"; // punt
      }
    }
    return fieldString;
  }

  /**
   * Convert array list to PG String representation (e.g. {0,1,2}).
   */
  private String toString(ArrayDecoding.PgArrayList list) throws SQLException {
    if (list == null) {
      return "NULL";
    }

    StringBuilder b = new StringBuilder().append('{');

    char delim = getPgType().getDelimiter();

    for (int i = 0; i < list.size(); i++) {
      Object v = list.get(i);

      if (i > 0) {
        b.append(delim);
      }

      if (v == null) {
        b.append("NULL");
      } else if (v instanceof ArrayDecoding.PgArrayList) {
        b.append(toString((ArrayDecoding.PgArrayList) v));
      } else {
        escapeArrayElement(b, (String) v);
      }
    }

    b.append('}');

    return b.toString();
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

  public byte @Nullable [] toBytes() throws SQLException {
    Object array = fieldArray;
    if (fieldBytes == null && array != null
        && getConnection().getPreferQueryMode() != PreferQueryMode.SIMPLE) {
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
    arrayList = null;
  }
}
