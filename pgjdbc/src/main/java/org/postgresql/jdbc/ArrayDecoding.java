/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.core.Parser;
import org.postgresql.jdbc2.ArrayAssistant;
import org.postgresql.jdbc2.ArrayAssistantRegistry;
import org.postgresql.util.GT;
import org.postgresql.util.PGbytea;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility for decoding arrays.
 *
 * <p>
 * See {@code ArrayEncoding} for description of the binary format of arrays.
 * </p>
 *
 * @author Brett Okken
 */
final class ArrayDecoding {

  /**
   * Array list implementation specific for storing PG array elements. If
   * {@link PgArrayList#dimensionsCount} is {@code 1}, the contents will be
   * {@link String}. For all larger <i>dimensionsCount</i>, the values will be
   * {@link PgArrayList} instances.
   */
  static final class PgArrayList extends ArrayList<@Nullable Object> {

    private static final long serialVersionUID = 1L;

    /**
     * How many dimensions.
     */
    int dimensionsCount = 1;

  }

  private interface ArrayDecoder<A extends @NonNull Object> {

    A createArray(@NonNegative int size);

    Object[] createMultiDimensionalArray(@NonNegative int[] sizes);

    boolean supportBinary();

    void populateFromBinary(A array, @NonNegative int index, @NonNegative int count, ByteBuffer bytes, BaseConnection connection)
        throws SQLException;

    void populateFromString(A array, List<@Nullable String> strings, BaseConnection connection) throws SQLException;
  }

  private abstract static class AbstractObjectStringArrayDecoder<A extends @NonNull Object> implements ArrayDecoder<A> {
    final Class<?> baseClazz;

    AbstractObjectStringArrayDecoder(Class<?> baseClazz) {
      this.baseClazz = baseClazz;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportBinary() {
      return false;
    }

    @SuppressWarnings("unchecked")
    @Override
    public A createArray(int size) {
      return (A) Array.newInstance(baseClazz, size);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object[] createMultiDimensionalArray(int[] sizes) {
      return (Object[]) Array.newInstance(baseClazz, sizes);
    }

    @Override
    public void populateFromBinary(A arr, int index, int count, ByteBuffer bytes, BaseConnection connection)
        throws SQLException {
      throw new SQLFeatureNotSupportedException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void populateFromString(A arr, List<@Nullable String> strings, BaseConnection connection) throws SQLException {
      final @Nullable Object[] array = (Object[]) arr;

      for (int i = 0, j = strings.size(); i < j; ++i) {
        final String stringVal = strings.get(i);
        array[i] = stringVal != null ? parseValue(stringVal, connection) : null;
      }
    }

    abstract Object parseValue(String stringVal, BaseConnection connection) throws SQLException;
  }

  private abstract static class AbstractObjectArrayDecoder<A extends @NonNull Object> extends AbstractObjectStringArrayDecoder<A> {

    AbstractObjectArrayDecoder(Class<?> baseClazz) {
      super(baseClazz);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportBinary() {
      return true;
    }

    @Override
    public void populateFromBinary(A arr, @NonNegative int index, @NonNegative int count, ByteBuffer bytes, BaseConnection connection)
        throws SQLException {
      final @Nullable Object[] array = (Object[]) arr;

      // skip through to the requested index
      for (int i = 0; i < index; ++i) {
        final int length = bytes.getInt();
        if (length > 0) {
          bytes.position(bytes.position() + length);
        }
      }

      for (int i = 0; i < count; ++i) {
        final int length = bytes.getInt();
        if (length != -1) {
          array[i] = parseValue(length, bytes, connection);
        } else {
          // explicitly set to null for reader's clarity
          array[i] = null;
        }
      }
    }

    abstract Object parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException;
  }

  private static final ArrayDecoder<Long[]> LONG_OBJ_ARRAY = new AbstractObjectArrayDecoder<Long[]>(Long.class) {

    @Override
    Object parseValue(int length, ByteBuffer bytes, BaseConnection connection) {
      return bytes.getLong();
    }

    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PgResultSet.toLong(stringVal);
    }
  };

  private static final ArrayDecoder<Long[]> INT4_UNSIGNED_OBJ_ARRAY = new AbstractObjectArrayDecoder<Long[]>(
      Long.class) {

    @Override
    Object parseValue(int length, ByteBuffer bytes, BaseConnection connection) {
      final long value = bytes.getInt() & 0xFFFFFFFFL;
      return value;
    }

    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PgResultSet.toLong(stringVal);
    }
  };

  private static final ArrayDecoder<Integer[]> INTEGER_OBJ_ARRAY = new AbstractObjectArrayDecoder<Integer[]>(
      Integer.class) {

    @Override
    Object parseValue(int length, ByteBuffer bytes, BaseConnection connection) {
      return bytes.getInt();
    }

    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PgResultSet.toInt(stringVal);
    }
  };

  private static final ArrayDecoder<Short[]> SHORT_OBJ_ARRAY = new AbstractObjectArrayDecoder<Short[]>(Short.class) {

    @Override
    Object parseValue(int length, ByteBuffer bytes, BaseConnection connection) {
      return bytes.getShort();
    }

    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PgResultSet.toShort(stringVal);
    }
  };

  private static final ArrayDecoder<Double[]> DOUBLE_OBJ_ARRAY = new AbstractObjectArrayDecoder<Double[]>(
      Double.class) {

    @Override
    Object parseValue(int length, ByteBuffer bytes, BaseConnection connection) {
      return bytes.getDouble();
    }

    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PgResultSet.toDouble(stringVal);
    }
  };

  private static final ArrayDecoder<Float[]> FLOAT_OBJ_ARRAY = new AbstractObjectArrayDecoder<Float[]>(Float.class) {

    @Override
    Object parseValue(int length, ByteBuffer bytes, BaseConnection connection) {
      return bytes.getFloat();
    }

    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PgResultSet.toFloat(stringVal);
    }
  };

  private static final ArrayDecoder<Boolean[]> BOOLEAN_OBJ_ARRAY = new AbstractObjectArrayDecoder<Boolean[]>(
      Boolean.class) {

    @Override
    Object parseValue(int length, ByteBuffer bytes, BaseConnection connection) {
      return bytes.get() == 1;
    }

    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return BooleanTypeUtil.fromString(stringVal);
    }
  };

  private static final ArrayDecoder<String[]> STRING_ARRAY = new AbstractObjectArrayDecoder<String[]>(String.class) {

    @Override
    Object parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {
      assert bytes.hasArray();
      final byte[] byteArray = bytes.array();
      final int offset = bytes.arrayOffset() + bytes.position();

      String val;
      try {
        val = connection.getEncoding().decode(byteArray, offset, length);
      } catch (IOException e) {
        throw new PSQLException(GT.tr(
            "Invalid character data was found.  This is most likely caused by stored data containing characters that are invalid for the character set the database was created in.  The most common example of this is storing 8bit data in a SQL_ASCII database."),
            PSQLState.DATA_ERROR, e);
      }
      bytes.position(bytes.position() + length);
      return val;
    }

    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return stringVal;
    }
  };

  private static final ArrayDecoder<byte[][]> BYTE_ARRAY_ARRAY = new AbstractObjectArrayDecoder<byte[][]>(
      byte[].class) {

    /**
     * {@inheritDoc}
     */
    @Override
    Object parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {
      final byte[] array = new byte[length];
      bytes.get(array);
      return array;
    }

    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PGbytea.toBytes(stringVal.getBytes(StandardCharsets.US_ASCII));
    }
  };

  private static final ArrayDecoder<BigDecimal[]> BIG_DECIMAL_STRING_DECODER = new AbstractObjectStringArrayDecoder<BigDecimal[]>(
      BigDecimal.class) {

    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PgResultSet.toBigDecimal(stringVal);
    }
  };

  private static final ArrayDecoder<String[]> STRING_ONLY_DECODER = new AbstractObjectStringArrayDecoder<String[]>(
      String.class) {

    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return stringVal;
    }
  };

  private static final ArrayDecoder<java.sql.Date[]> DATE_DECODER = new AbstractObjectStringArrayDecoder<java.sql.Date[]>(
      java.sql.Date.class) {

    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return connection.getTimestampUtils().toDate(null, stringVal);
    }
  };

  private static final ArrayDecoder<java.sql.Time[]> TIME_DECODER = new AbstractObjectStringArrayDecoder<java.sql.Time[]>(
      java.sql.Time.class) {

    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return connection.getTimestampUtils().toTime(null, stringVal);
    }
  };

  private static final ArrayDecoder<java.sql.Timestamp[]> TIMESTAMP_DECODER = new AbstractObjectStringArrayDecoder<java.sql.Timestamp[]>(
      java.sql.Timestamp.class) {

    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return connection.getTimestampUtils().toTimestamp(null, stringVal);
    }
  };

  /**
   * Maps from base type oid to {@link ArrayDecoder} capable of processing
   * entries.
   */
  @SuppressWarnings("rawtypes")
  private static final Map<Integer, ArrayDecoder> OID_TO_DECODER = new HashMap<Integer, ArrayDecoder>(
      (int) (21 / .75) + 1);

  static {
    OID_TO_DECODER.put(Oid.OID, INT4_UNSIGNED_OBJ_ARRAY);
    OID_TO_DECODER.put(Oid.INT8, LONG_OBJ_ARRAY);
    OID_TO_DECODER.put(Oid.INT4, INTEGER_OBJ_ARRAY);
    OID_TO_DECODER.put(Oid.INT2, SHORT_OBJ_ARRAY);
    OID_TO_DECODER.put(Oid.MONEY, DOUBLE_OBJ_ARRAY);
    OID_TO_DECODER.put(Oid.FLOAT8, DOUBLE_OBJ_ARRAY);
    OID_TO_DECODER.put(Oid.FLOAT4, FLOAT_OBJ_ARRAY);
    OID_TO_DECODER.put(Oid.TEXT, STRING_ARRAY);
    OID_TO_DECODER.put(Oid.VARCHAR, STRING_ARRAY);
    // 42.2.x decodes jsonb array as String rather than PGobject
    OID_TO_DECODER.put(Oid.JSONB, STRING_ONLY_DECODER);
    OID_TO_DECODER.put(Oid.BIT, BOOLEAN_OBJ_ARRAY);
    OID_TO_DECODER.put(Oid.BOOL, BOOLEAN_OBJ_ARRAY);
    OID_TO_DECODER.put(Oid.BYTEA, BYTE_ARRAY_ARRAY);
    OID_TO_DECODER.put(Oid.NUMERIC, BIG_DECIMAL_STRING_DECODER);
    OID_TO_DECODER.put(Oid.BPCHAR, STRING_ONLY_DECODER);
    OID_TO_DECODER.put(Oid.CHAR, STRING_ONLY_DECODER);
    OID_TO_DECODER.put(Oid.JSON, STRING_ONLY_DECODER);
    OID_TO_DECODER.put(Oid.DATE, DATE_DECODER);
    OID_TO_DECODER.put(Oid.TIME, TIME_DECODER);
    OID_TO_DECODER.put(Oid.TIMETZ, TIME_DECODER);
    OID_TO_DECODER.put(Oid.TIMESTAMP, TIMESTAMP_DECODER);
    OID_TO_DECODER.put(Oid.TIMESTAMPTZ, TIMESTAMP_DECODER);
  }

  @SuppressWarnings("rawtypes")
  private static final class ArrayAssistantObjectArrayDecoder extends AbstractObjectArrayDecoder {
    private final ArrayAssistant arrayAssistant;

    @SuppressWarnings("unchecked")
    ArrayAssistantObjectArrayDecoder(ArrayAssistant arrayAssistant) {
      super(arrayAssistant.baseType());
      this.arrayAssistant = arrayAssistant;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    Object parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {

      assert bytes.hasArray();
      final byte[] byteArray = bytes.array();
      final int offset = bytes.arrayOffset() + bytes.position();

      final Object val = arrayAssistant.buildElement(byteArray, offset, length);

      bytes.position(bytes.position() + length);
      return val;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return arrayAssistant.buildElement(stringVal);
    }
  }

  private static final class MappedTypeObjectArrayDecoder extends AbstractObjectArrayDecoder<Object[]> {

    private final String typeName;

    MappedTypeObjectArrayDecoder(String baseTypeName) {
      super(Object.class);
      this.typeName = baseTypeName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    Object parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {
      final byte[] copy = new byte[length];
      bytes.get(copy);
      return connection.getObject(typeName, null, copy);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return connection.getObject(typeName, stringVal, null);
    }
  }

  @SuppressWarnings("unchecked")
  private static <A extends @NonNull Object> ArrayDecoder<A> getDecoder(int oid, BaseConnection connection) throws SQLException {
    final Integer key = oid;
    @SuppressWarnings("rawtypes")
    final ArrayDecoder decoder = OID_TO_DECODER.get(key);
    if (decoder != null) {
      return decoder;
    }

    final ArrayAssistant assistant = ArrayAssistantRegistry.getAssistant(oid);

    if (assistant != null) {
      return new ArrayAssistantObjectArrayDecoder(assistant);
    }

    final String typeName = connection.getTypeInfo().getPGType(oid);
    if (typeName == null) {
      throw org.postgresql.Driver.notImplemented(PgArray.class, "readArray(data,oid)");
    }

    // 42.2.x should return enums as strings
    int type = connection.getTypeInfo().getSQLType(typeName);
    if (type == Types.CHAR || type == Types.VARCHAR) {
      return (ArrayDecoder<A>) STRING_ONLY_DECODER;
    }
    return (ArrayDecoder<A>) new MappedTypeObjectArrayDecoder(typeName);
  }

  /**
   * Reads binary representation of array into object model.
   *
   * @param index
   *          1 based index of where to start on outermost array.
   * @param count
   *          The number of items to return from outermost array (beginning at
   *          <i>index</i>).
   * @param bytes
   *          The binary representation of the array.
   * @param connection
   *          The connection the <i>bytes</i> were retrieved from.
   * @return The parsed array.
   * @throws SQLException
   *           For failures encountered during parsing.
   */
  @SuppressWarnings("unchecked")
  public static Object readBinaryArray(int index, int count, byte[] bytes, BaseConnection connection)
      throws SQLException {
    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
    buffer.order(ByteOrder.BIG_ENDIAN);
    final int dimensions = buffer.getInt();
    @SuppressWarnings("unused")
    final boolean hasNulls = buffer.getInt() != 0;
    final int elementOid = buffer.getInt();

    @SuppressWarnings("rawtypes")
    final ArrayDecoder decoder = getDecoder(elementOid, connection);

    if (!decoder.supportBinary()) {
      throw org.postgresql.Driver.notImplemented(PgArray.class, "readBinaryArray(data,oid)");
    }

    if (dimensions == 0) {
      return decoder.createArray(0);
    }

    final int adjustedSkipIndex = index > 0 ? index - 1 : 0;

    // optimize for single dimension array
    if (dimensions == 1) {
      int length = buffer.getInt();
      buffer.position(buffer.position() + 4);
      if (count > 0) {
        length = Math.min(length, count);
      }
      final Object array = decoder.createArray(length);
      decoder.populateFromBinary(array, adjustedSkipIndex, length, buffer, connection);
      return array;
    }

    final int[] dimensionLengths = new int[dimensions];
    for (int i = 0; i < dimensions; ++i) {
      dimensionLengths[i] = buffer.getInt();
      buffer.position(buffer.position() + 4);
    }

    if (count > 0) {
      dimensionLengths[0] = Math.min(count, dimensionLengths[0]);
    }

    final Object[] array = decoder.createMultiDimensionalArray(dimensionLengths);

    // TODO: in certain circumstances (no nulls, fixed size data types)
    // if adjustedSkipIndex is > 0, we could advance through the buffer rather than
    // parse our way through throwing away the results

    storeValues(array, decoder, buffer, adjustedSkipIndex, dimensionLengths, 0, connection);

    return array;
  }

  @SuppressWarnings("unchecked")
  private static <A extends @NonNull Object> void storeValues(A[] array, ArrayDecoder<A> decoder, ByteBuffer bytes,
      int skip, int[] dimensionLengths, int dim, BaseConnection connection) throws SQLException {
    assert dim <= dimensionLengths.length - 2;

    for (int i = 0; i < skip; ++i) {
      if (dim == dimensionLengths.length - 2) {
        decoder.populateFromBinary(array[0], 0, dimensionLengths[dim + 1], bytes, connection);
      } else {
        storeValues((@NonNull A @NonNull[]) array[0], decoder, bytes, 0, dimensionLengths, dim + 1, connection);
      }
    }

    for (int i = 0; i < dimensionLengths[dim]; ++i) {
      if (dim == dimensionLengths.length - 2) {
        decoder.populateFromBinary(array[i], 0, dimensionLengths[dim + 1], bytes, connection);
      } else {
        storeValues((@NonNull A @NonNull[]) array[i], decoder, bytes, 0, dimensionLengths, dim + 1, connection);
      }
    }
  }

  /**
   * Parses the string representation of an array into a {@link PgArrayList}.
   *
   * @param fieldString
   *          The array value to parse.
   * @param delim
   *          The delimiter character appropriate for the data type.
   * @return A {@link PgArrayList} representing the parsed <i>fieldString</i>.
   */
  static PgArrayList buildArrayList(String fieldString, char delim) {

    final PgArrayList arrayList = new PgArrayList();

    if (fieldString == null) {
      return arrayList;
    }

    final char[] chars = fieldString.toCharArray();
    StringBuilder buffer = null;
    boolean insideString = false;

    // needed for checking if NULL value occurred
    boolean wasInsideString = false;

    // array dimension arrays
    final List<PgArrayList> dims = new ArrayList<PgArrayList>();

    // currently processed array
    PgArrayList curArray = arrayList;

    // Starting with 8.0 non-standard (beginning index
    // isn't 1) bounds the dimensions are returned in the
    // data formatted like so "[0:3]={0,1,2,3,4}".
    // Older versions simply do not return the bounds.
    //
    // Right now we ignore these bounds, but we could
    // consider allowing these index values to be used
    // even though the JDBC spec says 1 is the first
    // index. I'm not sure what a client would like
    // to see, so we just retain the old behavior.
    int startOffset = 0;
    {
      if (chars[0] == '[') {
        while (chars[startOffset] != '=') {
          startOffset++;
        }
        startOffset++; // skip =
      }
    }

    for (int i = startOffset; i < chars.length; i++) {

      // escape character that we need to skip
      if (chars[i] == '\\') {
        i++;
      } else if (!insideString && chars[i] == '{') {
        // subarray start
        if (dims.isEmpty()) {
          dims.add(arrayList);
        } else {
          PgArrayList a = new PgArrayList();
          PgArrayList p = dims.get(dims.size() - 1);
          p.add(a);
          dims.add(a);
        }
        curArray = dims.get(dims.size() - 1);

        // number of dimensions
        {
          for (int t = i + 1; t < chars.length; t++) {
            if (Character.isWhitespace(chars[t])) {
              continue;
            } else if (chars[t] == '{') {
              curArray.dimensionsCount++;
            } else {
              break;
            }
          }
        }

        buffer = new StringBuilder();
        continue;
      } else if (chars[i] == '"') {
        // quoted element
        insideString = !insideString;
        wasInsideString = true;
        continue;
      } else if (!insideString && Parser.isArrayWhiteSpace(chars[i])) {
        // white space
        continue;
      } else if ((!insideString && (chars[i] == delim || chars[i] == '}')) || i == chars.length - 1) {
        // array end or element end
        // when character that is a part of array element
        if (chars[i] != '"' && chars[i] != '}' && chars[i] != delim && buffer != null) {
          buffer.append(chars[i]);
        }

        String b = buffer == null ? null : buffer.toString();

        // add element to current array
        if (b != null && (!b.isEmpty() || wasInsideString)) {
          curArray.add(!wasInsideString && b.equals("NULL") ? null : b);
        }

        wasInsideString = false;
        buffer = new StringBuilder();

        // when end of an array
        if (chars[i] == '}') {
          dims.remove(dims.size() - 1);

          // when multi-dimension
          if (!dims.isEmpty()) {
            curArray = dims.get(dims.size() - 1);
          }

          buffer = null;
        }

        continue;
      }

      if (buffer != null) {
        buffer.append(chars[i]);
      }
    }

    return arrayList;
  }

  /**
   * Reads {@code String} representation of array into object model.
   *
   * @param index
   *          1 based index of where to start on outermost array.
   * @param count
   *          The number of items to return from outermost array (beginning at
   *          <i>index</i>).
   * @param oid
   *          The oid of the base type of the array.
   * @param list
   *          The {@code #buildArrayList(String, char) processed} string
   *          representation of an array.
   * @param connection
   *          The connection the <i>bytes</i> were retrieved from.
   * @return The parsed array.
   * @throws SQLException
   *           For failures encountered during parsing.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public static Object readStringArray(int index, int count, int oid, PgArrayList list, BaseConnection connection)
      throws SQLException {

    final ArrayDecoder decoder = getDecoder(oid, connection);

    final int dims = list.dimensionsCount;

    if (dims == 0) {
      return decoder.createArray(0);
    }

    boolean sublist = false;

    int adjustedSkipIndex = 0;
    if (index > 1) {
      sublist = true;
      adjustedSkipIndex = index - 1;
    }

    int adjustedCount = list.size();
    if (count > 0 && count != adjustedCount) {
      sublist = true;
      adjustedCount = Math.min(adjustedCount, count);
    }

    final List adjustedList = sublist ? list.subList(adjustedSkipIndex, adjustedSkipIndex + adjustedCount) : list;

    if (dims == 1) {
      int length = adjustedList.size();
      if (count > 0) {
        length = Math.min(length, count);
      }
      final Object array = decoder.createArray(length);
      decoder.populateFromString(array, adjustedList, connection);
      return array;
    }

    // dimensions length array (to be used with
    // java.lang.reflect.Array.newInstance(Class<?>, int[]))
    final int[] dimensionLengths = new int[dims];
    dimensionLengths[0] = adjustedCount;
    {
      List tmpList = (List) adjustedList.get(0);
      for (int i = 1; i < dims; i++) {
        dimensionLengths[i] = tmpList.size();
        if (i != dims - 1) {
          tmpList = (List) tmpList.get(0);
        }
      }
    }

    final Object[] array = decoder.createMultiDimensionalArray(dimensionLengths);

    storeStringValues(array, decoder, adjustedList, dimensionLengths, 0, connection);

    return array;
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static <A extends @NonNull Object> void storeStringValues(A[] array, ArrayDecoder<A> decoder, List list, int @NonNull[] dimensionLengths,
      int dim, BaseConnection connection) throws SQLException {
    assert dim <= dimensionLengths.length - 2;

    for (int i = 0; i < dimensionLengths[dim]; ++i) {
      if (dim == dimensionLengths.length - 2) {
        decoder.populateFromString(array[i], (List<@Nullable String>) list.get(i), connection);
      } else {
        storeStringValues((@NonNull A @NonNull[]) array[i], decoder, (List) list.get(i), dimensionLengths, dim + 1, connection);
      }
    }
  }
}
