/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.Driver;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.core.Parser;
import org.postgresql.core.Tuple;
import org.postgresql.jdbc2.ArrayAssistant;
import org.postgresql.jdbc2.ArrayAssistantRegistry;
import org.postgresql.util.ByteConverter;
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
import java.sql.Date;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
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

  private interface FieldDecoder<E extends @NonNull Object> {

    boolean supportBinary();

    E parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException;

    E parseValue(String stringVal, BaseConnection connection) throws SQLException;

    Class<E> baseType();
  }

  public abstract static class AbstractObjectFieldDecoder<E extends @NonNull Object> implements FieldDecoder<E> {

    @Override
    public boolean supportBinary() {
      return true;
    }
  }

  private interface ArrayDecoder<A extends @NonNull Object> {

    A createArray(@NonNegative int size);

    Object[] createMultiDimensionalArray(@NonNegative int[] sizes);

    boolean supportBinary();

    void populateFromBinary(A array, @NonNegative int index, @NonNegative int count, ByteBuffer bytes, BaseConnection connection)
        throws SQLException;

    void populateFromString(A array, List<@Nullable String> strings, BaseConnection connection) throws SQLException;
  }

  private static class ArrayDecoderImpl<A extends @NonNull Object, E extends @NonNull Object> implements ArrayDecoder<A> {

    final Class<E> baseClazz;
    final FieldDecoder<E> fieldDecoder;

    ArrayDecoderImpl(FieldDecoder<E> fieldDecoder) {
      this.fieldDecoder = fieldDecoder;
      this.baseClazz = fieldDecoder.baseType();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportBinary() {
      return fieldDecoder.supportBinary();
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
      if (!supportBinary()) {
        throw new SQLFeatureNotSupportedException();
      }

      final @Nullable Object[] array = (Object[]) arr;

      // skip through to the requested index
      for (int i = 0; i < index; i++) {
        final int length = bytes.getInt();
        if (length > 0) {
          bytes.position(bytes.position() + length);
        }
      }

      for (int i = 0; i < count; i++) {
        final int length = bytes.getInt();
        if (length != -1) {
          array[i] = fieldDecoder.parseValue(length, bytes, connection);
        } else {
          // explicitly set to null for reader's clarity
          array[i] = null;
        }
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void populateFromString(A arr, List<@Nullable String> strings, BaseConnection connection) throws SQLException {
      final @Nullable Object[] array = (Object[]) arr;

      for (int i = 0, j = strings.size(); i < j; i++) {
        final String stringVal = strings.get(i);
        array[i] = stringVal != null ? fieldDecoder.parseValue(stringVal, connection) : null;
      }
    }

  }

  private static final FieldDecoder<Long> LONG_OBJ = new AbstractObjectFieldDecoder<Long>() {

    @Override
    public @NonNull Long parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {
      return bytes.getLong();
    }

    @Override
    public @NonNull Long parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PgResultSet.toLong(stringVal);
    }

    @Override
    public Class<Long> baseType() {
      return Long.class;
    }
  };

  private static final FieldDecoder<Long> INT4_UNSIGNED_OBJ = new AbstractObjectFieldDecoder<Long>() {

    @Override
    public Long parseValue(int length, ByteBuffer bytes, BaseConnection connection) {
      return bytes.getInt() & 0xFFFFFFFFL;
    }

    @Override
    public Long parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PgResultSet.toLong(stringVal);
    }

    @Override
    public Class<Long> baseType() {
      return Long.class;
    }
  };

  private static final FieldDecoder<Integer> INTEGER_OBJ = new AbstractObjectFieldDecoder<Integer>() {

    @Override
    public Integer parseValue(int length, ByteBuffer bytes, BaseConnection connection) {
      return bytes.getInt();
    }

    @Override
    public Integer parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PgResultSet.toInt(stringVal);
    }

    @Override
    public Class<Integer> baseType() {
      return Integer.class;
    }
  };

  private static final FieldDecoder<Short> SHORT_OBJ = new AbstractObjectFieldDecoder<Short>() {

    @Override
    public Short parseValue(int length, ByteBuffer bytes, BaseConnection connection) {
      return bytes.getShort();
    }

    @Override
    public Short parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PgResultSet.toShort(stringVal);
    }

    @Override
    public Class<Short> baseType() {
      return Short.class;
    }
  };

  private static final FieldDecoder<Double> DOUBLE_OBJ = new AbstractObjectFieldDecoder<Double>() {

    @Override
    public Double parseValue(int length, ByteBuffer bytes, BaseConnection connection) {
      return bytes.getDouble();
    }

    @Override
    public Double parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PgResultSet.toDouble(stringVal);
    }

    @Override
    public Class<Double> baseType() {
      return Double.class;
    }
  };

  private static final FieldDecoder<Float> FLOAT_OBJ = new AbstractObjectFieldDecoder<Float>() {

    @Override
    public Float parseValue(int length, ByteBuffer bytes, BaseConnection connection) {
      return bytes.getFloat();
    }

    @Override
    public Float parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PgResultSet.toFloat(stringVal);
    }

    @Override
    public Class<Float> baseType() {
      return Float.class;
    }
  };

  private static final FieldDecoder<Boolean> BOOLEAN_OBJ = new AbstractObjectFieldDecoder<Boolean>() {

    @Override
    public Boolean parseValue(int length, ByteBuffer bytes, BaseConnection connection) {
      return bytes.get() == 1;
    }

    @Override
    public Boolean parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return BooleanTypeUtil.fromString(stringVal);
    }

    @Override
    public Class<Boolean> baseType() {
      return Boolean.class;
    }
  };

  private static final FieldDecoder<String> STRING = new AbstractObjectFieldDecoder<String>() {

    @Override
    public String parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {
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
    public String parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return stringVal;
    }

    @Override
    public Class<String> baseType() {
      return String.class;
    }
  };

  private static final FieldDecoder<String> JSONB_DECODER = new AbstractObjectFieldDecoder<String>() {

    @Override
    public String parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {
      assert bytes.hasArray();
      // jsonb has an extra byte 'version' in its binary encoding
      bytes.get();
      length -= 1;

      // 42.2.x decodes jsonb array element as String rather than PGobject
      return STRING.parseValue(length, bytes, connection);
    }

    @Override
    public String parseValue(String stringVal, BaseConnection connection) throws SQLException {
      // 42.2.x decodes jsonb array element as String rather than PGobject
      return STRING.parseValue(stringVal, connection);
    }

    @Override
    public Class<String> baseType() {
      return String.class;
    }
  };

  private static final FieldDecoder<byte[]> BYTE_ARRAY = new AbstractObjectFieldDecoder<byte[]>() {

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {
      final byte[] array = new byte[length];
      bytes.get(array);
      return array;
    }

    @Override
    public byte[] parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PGbytea.toBytes(stringVal.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public Class<byte[]> baseType() {
      return byte[].class;
    }
  };

  private static final FieldDecoder<BigDecimal> BIG_DECIMAL_STRING_DECODER = new AbstractObjectFieldDecoder<BigDecimal>() {

    @Override
    public BigDecimal parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {
      final byte[] numericVal = new byte[length];
      bytes.get(numericVal);
      return (BigDecimal) ByteConverter.numeric(numericVal);
    }

    @Override
    public BigDecimal parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return PgResultSet.toBigDecimal(stringVal);
    }

    @Override
    public Class<BigDecimal> baseType() {
      return BigDecimal.class;
    }
  };

  private static final FieldDecoder<Date> DATE_DECODER = new AbstractObjectFieldDecoder<Date>() {

    @Override
    public Date parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {
      final byte[] dateVal = new byte[length];
      bytes.get(dateVal);
      return connection.getTimestampUtils().toDateBin(null, dateVal);
    }

    @Override
    public Date parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return connection.getTimestampUtils().toDate(null, stringVal.getBytes());
    }

    @Override
    public Class<Date> baseType() {
      return Date.class;
    }
  };

  private static final FieldDecoder<Time> TIME_DECODER = new AbstractObjectFieldDecoder<Time>() {

    @Override
    public Time parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {
      final byte[] timeVal = new byte[length];
      bytes.get(timeVal);
      return connection.getTimestampUtils().toTimeBin(null, timeVal);
    }

    @Override
    public Time parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return connection.getTimestampUtils().toTime(null, stringVal.getBytes());
    }

    @Override
    public Class<Time> baseType() {
      return Time.class;
    }
  };

  private static final FieldDecoder<Timestamp> TIMESTAMP_DECODER = new AbstractObjectFieldDecoder<Timestamp>() {

    @Override
    public Timestamp parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {
      byte[] timestampVal = new byte[length];
      bytes.get(timestampVal);
      return connection.getTimestampUtils().toTimestampBin(null, timestampVal, false);
    }

    @Override
    public Timestamp parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return connection.getTimestampUtils().toTimestamp(null, stringVal.getBytes());
    }

    @Override
    public Class<Timestamp> baseType() {
      return Timestamp.class;
    }
  };

  private static final FieldDecoder<Timestamp> TIMESTAMPZ_DECODER = new AbstractObjectFieldDecoder<Timestamp>() {

    @Override
    public Timestamp parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {
      byte[] timestampVal = new byte[length];
      bytes.get(timestampVal);
      return connection.getTimestampUtils().toTimestampBin(null, timestampVal, true);
    }

    @Override
    public Timestamp parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return TIMESTAMP_DECODER.parseValue(stringVal, connection);
    }

    @Override
    public Class<Timestamp> baseType() {
      return Timestamp.class;
    }
  };

  /**
   * Maps from base type oid to {@link FieldDecoder} capable of processing
   * entries.
   */
  @SuppressWarnings("rawtypes")
  private static final Map<Integer, FieldDecoder> OID_TO_DECODER = new HashMap<>(
      (int) (22 / .75) + 1);

  static {
    OID_TO_DECODER.put(Oid.OID, INT4_UNSIGNED_OBJ);
    OID_TO_DECODER.put(Oid.INT8, LONG_OBJ);
    OID_TO_DECODER.put(Oid.INT4, INTEGER_OBJ);
    OID_TO_DECODER.put(Oid.INT2, SHORT_OBJ);
    OID_TO_DECODER.put(Oid.MONEY, DOUBLE_OBJ);
    OID_TO_DECODER.put(Oid.FLOAT8, DOUBLE_OBJ);
    OID_TO_DECODER.put(Oid.FLOAT4, FLOAT_OBJ);
    OID_TO_DECODER.put(Oid.TEXT, STRING);
    OID_TO_DECODER.put(Oid.VARCHAR, STRING);
    OID_TO_DECODER.put(Oid.JSONB, JSONB_DECODER);
    OID_TO_DECODER.put(Oid.BIT, BOOLEAN_OBJ);
    OID_TO_DECODER.put(Oid.BOOL, BOOLEAN_OBJ);
    OID_TO_DECODER.put(Oid.BYTEA, BYTE_ARRAY);
    OID_TO_DECODER.put(Oid.NUMERIC, BIG_DECIMAL_STRING_DECODER);
    OID_TO_DECODER.put(Oid.BPCHAR, STRING);
    OID_TO_DECODER.put(Oid.CHAR, STRING);
    OID_TO_DECODER.put(Oid.JSON, STRING);
    OID_TO_DECODER.put(Oid.DATE, DATE_DECODER);
    OID_TO_DECODER.put(Oid.TIME, TIME_DECODER);
    OID_TO_DECODER.put(Oid.TIMETZ, TIME_DECODER);
    OID_TO_DECODER.put(Oid.TIMESTAMP, TIMESTAMP_DECODER);
    OID_TO_DECODER.put(Oid.TIMESTAMPTZ, TIMESTAMPZ_DECODER);
  }

  @SuppressWarnings("rawtypes")
  private static final class ArrayAssistantFieldDecoder extends AbstractObjectFieldDecoder {
    private final ArrayAssistant arrayAssistant;

    ArrayAssistantFieldDecoder(ArrayAssistant arrayAssistant) {
      this.arrayAssistant = arrayAssistant;
    }

    @Override
    public Class<?> baseType() {
      return arrayAssistant.baseType();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {

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
    public Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return arrayAssistant.buildElement(stringVal);
    }
  }

  private static final class StructTypeDecoder extends AbstractObjectFieldDecoder<Struct> {

    private final String typeName;

    StructTypeDecoder(String typeName) {
      this.typeName = typeName;
    }

    @Override
    public Class<Struct> baseType() {
      return Struct.class;
    }

    @Override
    public Struct parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {
      final PgStructDescriptor descriptor = castNonNull(connection.getTypeInfo().getPgStructDescriptor(typeName));

      final byte[] structValue = new byte[length];
      bytes.get(structValue);

      final Tuple row = PgCompositeTypeUtil.fromBytes(structValue, descriptor.pgAttributes().length);

      return buildStruct(descriptor, row, connection, true);
    }

    @Override
    public Struct parseValue(String stringVal, BaseConnection connection) throws SQLException {
      final PgStructDescriptor descriptor = castNonNull(connection.getTypeInfo().getPgStructDescriptor(typeName));

      final Tuple row = PgCompositeTypeUtil.fromString(stringVal, descriptor.pgAttributes().length);

      return buildStruct(descriptor, row, connection, false);
    }

    private Struct buildStruct(PgStructDescriptor descriptor, Tuple row, BaseConnection connection, boolean isBinary) throws SQLException {
      final PgAttribute[] fields = descriptor.pgAttributes();
      final Object[] attributes = new Object[fields.length];
      for (int i = 0; i < attributes.length; i++) {
        final PgAttribute field = fields[i];
        final byte[] value = row.get(i);

        if (value == null) {
          attributes[i] = null;
          continue;
        }

        @SuppressWarnings("rawtypes")
        final FieldDecoder decoder = getFieldDecoder(field.oid(), connection);
        if (isBinary) {
          final ByteBuffer buffer = ByteBuffer.wrap(value);
          attributes[i] = decoder.parseValue(value.length, buffer, connection);
        } else {
          String decodedValue;
          try {
            decodedValue = connection.getEncoding().decode(value);
          } catch (IOException e) {
            throw new PSQLException(GT.tr(
                "Invalid character data was found.  This is most likely caused by stored data containing characters that are invalid for the character set the database was created in.  The most common example of this is storing 8bit data in a SQL_ASCII database."),
                PSQLState.DATA_ERROR, e);
          }
          attributes[i] = decoder.parseValue(decodedValue, connection);
        }
      }
      return new PgStruct(descriptor, attributes, connection);
    }
  }

  private static final class MappedTypeObjectDecoder extends AbstractObjectFieldDecoder<Object> {

    private final String typeName;

    MappedTypeObjectDecoder(String typeName) {
      this.typeName = typeName;
    }

    @Override
    public Class<Object> baseType() {
      return Object.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object parseValue(int length, ByteBuffer bytes, BaseConnection connection) throws SQLException {
      final byte[] copy = new byte[length];
      bytes.get(copy);
      return connection.getObject(typeName, null, copy);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object parseValue(String stringVal, BaseConnection connection) throws SQLException {
      return connection.getObject(typeName, stringVal, null);
    }
  }

  private static <A extends @NonNull Object> ArrayDecoder<A> getDecoder(int oid, BaseConnection connection) throws SQLException {
    @SuppressWarnings("rawtypes")
    final FieldDecoder decoder = getFieldDecoder(oid, connection);
    //noinspection unchecked
    return (ArrayDecoder<A>) new ArrayDecoderImpl<>(decoder);
  }

  @SuppressWarnings("unchecked")
  private static <A extends @NonNull Object> FieldDecoder<A> getFieldDecoder(int oid, BaseConnection connection) throws SQLException {
    final Integer key = oid;
    @SuppressWarnings("rawtypes")
    final FieldDecoder decoder = OID_TO_DECODER.get(key);
    if (decoder != null) {
      return decoder;
    }

    final ArrayAssistant assistant = ArrayAssistantRegistry.getAssistant(oid);

    if (assistant != null) {
      return new ArrayAssistantFieldDecoder(assistant);
    }

    final String typeName = connection.getTypeInfo().getPGType(oid);
    if (typeName == null) {
      throw Driver.notImplemented(PgArray.class, "readArray(data,oid)");
    }

    // 42.2.x should return enums as strings
    int type = connection.getTypeInfo().getSQLType(typeName);
    if (type == Types.CHAR || type == Types.VARCHAR) {
      return (FieldDecoder<A>) STRING;
    } else if (type == Types.STRUCT) {
      return (FieldDecoder<A>) new StructTypeDecoder(typeName);
    }
    return (FieldDecoder<A>) new MappedTypeObjectDecoder(typeName);
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
      throw Driver.notImplemented(PgArray.class, "readBinaryArray(data,oid)");
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
    for (int i = 0; i < dimensions; i++) {
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

    for (int i = 0; i < skip; i++) {
      if (dim == dimensionLengths.length - 2) {
        decoder.populateFromBinary(array[0], 0, dimensionLengths[dim + 1], bytes, connection);
      } else {
        storeValues((@NonNull A @NonNull[]) array[0], decoder, bytes, 0, dimensionLengths, dim + 1, connection);
      }
    }

    for (int i = 0; i < dimensionLengths[dim]; i++) {
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
    final List<PgArrayList> dims = new ArrayList<>();

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
          curArray.add(!wasInsideString && "NULL".equals(b) ? null : b);
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
  @SuppressWarnings({"unchecked", "rawtypes"})
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
        // TODO: tmpList always non-null?
        dimensionLengths[i] = castNonNull(tmpList, "first element of adjustedList is null").size();
        if (i != dims - 1) {
          tmpList = (List) tmpList.get(0);
        }
      }
    }

    final Object[] array = decoder.createMultiDimensionalArray(dimensionLengths);

    storeStringValues(array, decoder, adjustedList, dimensionLengths, 0, connection);

    return array;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static <A extends @NonNull Object> void storeStringValues(A[] array, ArrayDecoder<A> decoder, List list, int [] dimensionLengths,
      int dim, BaseConnection connection) throws SQLException {
    assert dim <= dimensionLengths.length - 2;

    for (int i = 0; i < dimensionLengths[dim]; i++) {
      Object element = castNonNull(list.get(i), "list.get(i)");
      if (dim == dimensionLengths.length - 2) {
        decoder.populateFromString(array[i], (List<@Nullable String>) element, connection);
      } else {
        storeStringValues((@NonNull A @NonNull[]) array[i], decoder, (List) element, dimensionLengths, dim + 1, connection);
      }
    }
  }
}
