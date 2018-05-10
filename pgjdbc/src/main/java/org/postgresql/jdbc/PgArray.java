/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.Encoding;
import org.postgresql.core.Field;
import org.postgresql.core.Oid;
import org.postgresql.core.Tuple;
import org.postgresql.jdbc2.ArrayAssistant;
import org.postgresql.jdbc2.ArrayAssistantRegistry;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PGbytea;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * <p>Array is used collect one column of query result data.</p>
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
public class PgArray implements java.sql.Array {

  static {
    ArrayAssistantRegistry.register(Oid.UUID, new UUIDArrayAssistant());
    ArrayAssistantRegistry.register(Oid.UUID_ARRAY, new UUIDArrayAssistant());
  }

  /**
   * Array list implementation specific for storing PG array elements.
   */
  private static class PgArrayList extends ArrayList<@Nullable Object> {

    private static final long serialVersionUID = 2052783752654562677L;

    /**
     * How many dimensions.
     */
    int dimensionsCount = 1;

  }

  /**
   * A database connection.
   */
  protected @Nullable BaseConnection connection;

  /**
   * The OID of this field.
   */
  private final int oid;

  /**
   * Field value as String.
   */
  protected @Nullable String fieldString;

  /**
   * Whether Object[] should be used instead primitive arrays. Object[] can contain null elements.
   * It should be set to <Code>true</Code> if
   * {@link BaseConnection#haveMinimumCompatibleVersion(String)} returns <Code>true</Code> for
   * argument "8.3".
   */
  private final boolean useObjects;

  /**
   * Value of field as {@link PgArrayList}. Will be initialized only once within
   * {@link #buildArrayList()}.
   */
  protected @Nullable PgArrayList arrayList;

  protected byte @Nullable [] fieldBytes;

  private PgArray(BaseConnection connection, int oid) throws SQLException {
    this.connection = connection;
    this.oid = oid;
    this.useObjects = true;
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

  private BaseConnection getConnection() {
    return castNonNull(connection);
  }

  @SuppressWarnings("return.type.incompatible")
  public Object getArray() throws SQLException {
    return getArrayImpl(1, 0, null);
  }

  @SuppressWarnings("return.type.incompatible")
  public Object getArray(long index, int count) throws SQLException {
    return getArrayImpl(index, count, null);
  }

  @SuppressWarnings("return.type.incompatible")
  public Object getArrayImpl(Map<String, Class<?>> map) throws SQLException {
    return getArrayImpl(1, 0, map);
  }

  @SuppressWarnings("return.type.incompatible")
  public Object getArray(Map<String, Class<?>> map) throws SQLException {
    return getArrayImpl(map);
  }

  @SuppressWarnings("return.type.incompatible")
  public Object getArray(long index, int count, @Nullable Map<String, Class<?>> map)
      throws SQLException {
    return getArrayImpl(index, count, map);
  }

  public @Nullable Object getArrayImpl(long index, int count, @Nullable Map<String, Class<?>> map)
      throws SQLException {

    // for now maps aren't supported.
    if (map != null && !map.isEmpty()) {
      throw org.postgresql.Driver.notImplemented(this.getClass(), "getArrayImpl(long,int,Map)");
    }

    // array index is out of range
    if (index < 1) {
      throw new PSQLException(GT.tr("The array index is out of range: {0}", index),
          PSQLState.DATA_ERROR);
    }

    if (fieldBytes != null) {
      return readBinaryArray(fieldBytes, (int) index, count);
    }

    if (fieldString == null) {
      return null;
    }

    PgArrayList arrayList = buildArrayList();

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

    return buildArray(arrayList, (int) index, count);
  }

  private Object readBinaryArray(byte[] fieldBytes, int index, int count)
      throws SQLException {
    int dimensions = ByteConverter.int4(fieldBytes, 0);
    // next 4 bytes indicate if null values are present or not
    // 0=no-nulls, 1=has-nulls
    // skip because it does not affect how we handle the data
    int elementOid = ByteConverter.int4(fieldBytes, 8);
    int pos = 12;
    int[] dims = new int[dimensions];
    for (int d = 0; d < dimensions; ++d) {
      dims[d] = ByteConverter.int4(fieldBytes, pos);
      pos += 4;
      /* int lbound = ByteConverter.int4(fieldBytes, pos); */
      pos += 4;
    }
    if (dimensions == 0) {
      return java.lang.reflect.Array.newInstance(elementOidToClass(elementOid), 0);
    }
    if (count > 0) {
      dims[0] = Math.min(count, dims[0]);
    }
    // in case of multi-dimensional array, an index greater than 1 indicates number
    // of outer level values to skip.
    final int toSkip = dims.length == 1 ? 0 : index - 1;
    Object arr = java.lang.reflect.Array.newInstance(elementOidToClass(elementOid), dims);
    try {
      storeValues((Object[]) arr, elementOid, dims, pos, 0, index, toSkip);
    } catch (IOException ioe) {
      throw new PSQLException(GT.tr(
          "Invalid character data was found.  This is most likely caused by stored data containing characters that are invalid for the character set the database was created in.  The most common example of this is storing 8bit data in a SQL_ASCII database."),
          PSQLState.DATA_ERROR, ioe);
    }
    return arr;
  }

  private int storeValues(final Object[] arr, int elementOid, final int[] dims, int pos, final int thisDimension,
      int index, int toSkip) throws SQLException, IOException {
    if (thisDimension == dims.length - 1) {
      for (int i = 1; i < index; ++i) {
        int len = ByteConverter.int4(fieldBytes, pos);
        pos += 4;
        if (len != -1) {
          pos += len;
        }
      }
      for (int i = 0; i < dims[thisDimension]; ++i) {
        int len = ByteConverter.int4(fieldBytes, pos);
        pos += 4;
        if (len == -1) {
          arr[i] = null;
          continue;
        }
        switch (elementOid) {
          case Oid.INT2:
            arr[i] = ByteConverter.int2(fieldBytes, pos);
            break;
          case Oid.INT4:
            arr[i] = ByteConverter.int4(fieldBytes, pos);
            break;
          case Oid.INT8:
            arr[i] = ByteConverter.int8(fieldBytes, pos);
            break;
          case Oid.FLOAT4:
            arr[i] = ByteConverter.float4(fieldBytes, pos);
            break;
          case Oid.FLOAT8:
            arr[i] = ByteConverter.float8(fieldBytes, pos);
            break;
          case Oid.TEXT:
          case Oid.VARCHAR:
            Encoding encoding = connection.getEncoding();
            arr[i] = encoding.decode(fieldBytes, pos, len);
            break;
          case Oid.BOOL:
            arr[i] = ByteConverter.bool(fieldBytes, pos);
            break;
          case Oid.BYTEA:
            arr[i] = new byte[len];
            System.arraycopy(fieldBytes, pos, arr[i], 0, len);
            break;
          default:
            ArrayAssistant arrAssistant = ArrayAssistantRegistry.getAssistant(elementOid);
            if (arrAssistant != null) {
              arr[i] = arrAssistant.buildElement(fieldBytes, pos, len);
            }
        }
        pos += len;
      }
    } else {
      for (int i = 0; i < toSkip; ++i) {
        pos = storeValues((Object[]) arr[0], elementOid, dims, pos, thisDimension + 1, 0, 0);
      }
      for (int i = 0; i < dims[thisDimension]; ++i) {
        pos = storeValues((Object[]) arr[i], elementOid, dims, pos, thisDimension + 1, 0, 0);
      }
    }
    return pos;
  }

  private ResultSet readBinaryResultSet(byte[] fieldBytes, int index, int count)
      throws SQLException {
    int dimensions = ByteConverter.int4(fieldBytes, 0);
    // int flags = ByteConverter.int4(fieldBytes, 4); // bit 0: 0=no-nulls, 1=has-nulls
    int elementOid = ByteConverter.int4(fieldBytes, 8);
    int pos = 12;
    int[] dims = new int[dimensions];
    for (int d = 0; d < dimensions; ++d) {
      dims[d] = ByteConverter.int4(fieldBytes, pos);
      pos += 4;
      /* int lbound = ByteConverter.int4(fieldBytes, pos); */
      pos += 4;
    }
    if (count > 0 && dimensions > 0) {
      dims[0] = Math.min(count, dims[0]);
    }
    List<Tuple> rows = new ArrayList<Tuple>();
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
      for (int i = 1; i < index; ++i) {
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
      for (int i = 1; i < index; ++i) {
        int len = ByteConverter.int4(fieldBytes, pos);
        pos += 4;
        if (len != -1) {
          pos += len;
        }
      }
      for (int i = 0; i < dims[thisDimension]; ++i) {
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
      for (int i = 1; i < index; ++i) {
        pos = calcRemainingDataLength(fieldBytes, dims, pos, elementOid, nextDimension);
      }
      for (int i = 0; i < dims[thisDimension]; ++i) {
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

  private int calcRemainingDataLength(byte[] fieldBytes,
      int[] dims, int pos, int elementOid, int thisDimension) {
    if (thisDimension == dims.length - 1) {
      for (int i = 0; i < dims[thisDimension]; ++i) {
        int len = ByteConverter.int4(fieldBytes, pos);
        pos += 4;
        if (len == -1) {
          continue;
        }
        pos += len;
      }
    } else {
      pos = calcRemainingDataLength(fieldBytes, dims, elementOid, pos, thisDimension + 1);
    }
    return pos;
  }

  private Class<?> elementOidToClass(int oid) throws SQLException {
    switch (oid) {
      case Oid.INT2:
        return Short.class;
      case Oid.INT4:
        return Integer.class;
      case Oid.INT8:
        return Long.class;
      case Oid.FLOAT4:
        return Float.class;
      case Oid.FLOAT8:
        return Double.class;
      case Oid.NUMERIC:
        return BigDecimal.class;
      case Oid.TEXT:
      case Oid.VARCHAR:
        return String.class;
      case Oid.BOOL:
        return Boolean.class;
      case Oid.BYTEA:
        return byte[].class;
      default:
        ArrayAssistant arrElemBuilder = ArrayAssistantRegistry.getAssistant(oid);
        if (arrElemBuilder != null) {
          return arrElemBuilder.baseType();
        }

        throw org.postgresql.Driver.notImplemented(this.getClass(), "readBinaryArray(data,oid)");
    }
  }

  /**
   * Build {@link ArrayList} from field's string input. As a result of this method
   * {@link #arrayList} is build. Method can be called many times in order to make sure that array
   * list is ready to use, however {@link #arrayList} will be set only once during first call.
   */
  private synchronized PgArrayList buildArrayList() throws SQLException {
    PgArrayList arrayList = this.arrayList;
    if (arrayList != null) {
      return arrayList;
    }

    this.arrayList = arrayList = new PgArrayList();

    char delim = getConnection().getTypeInfo().getArrayDelimiter(oid);

    if (fieldString != null) {

      char[] chars = fieldString.toCharArray();
      StringBuilder buffer = null;
      boolean insideString = false;
      boolean wasInsideString = false; // needed for checking if NULL
      // value occurred
      List<PgArrayList> dims = new ArrayList<PgArrayList>(); // array dimension arrays
      PgArrayList curArray = arrayList; // currently processed array

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
        } else if (!insideString && Character.isWhitespace(chars[i])) {
          // white space
          continue;
        } else if ((!insideString && (chars[i] == delim || chars[i] == '}'))
            || i == chars.length - 1) {
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
    }
    return arrayList;
  }

  /**
   * Convert {@link ArrayList} to array.
   *
   * @param input list to be converted into array
   */
  private Object buildArray(PgArrayList input, int index, int count) throws SQLException {

    if (count < 0) {
      count = input.size();
    }

    // array to be returned
    Object ret = null;

    // how many dimensions
    int dims = input.dimensionsCount;

    // dimensions length array (to be used with java.lang.reflect.Array.newInstance(Class<?>,
    // int[]))
    int[] dimsLength = dims > 1 ? new int[dims] : null;
    if (dimsLength != null) {
      for (int i = 0; i < dims; i++) {
        dimsLength[i] = (i == 0 ? count : 0);
      }
    }

    // array elements counter
    int length = 0;

    // array elements type
    final int type =
        getConnection().getTypeInfo().getSQLType(
            getConnection().getTypeInfo().getPGArrayElement(oid));

    if (type == Types.BIT) {
      boolean[] pa = null; // primitive array
      @Nullable Object @Nullable [] oa = null; // objects array

      if (dimsLength != null || useObjects) {
        ret = oa = (dimsLength != null
            ? (Object[]) java.lang.reflect.Array
                .newInstance(useObjects ? Boolean.class : boolean.class, dimsLength)
            : new Boolean[count]);
      } else {
        ret = pa = new boolean[count];
      }

      // add elements
      for (; count > 0; count--) {
        Object o = input.get(index++);

        if (oa != null) {
          oa[length++] = o == null ? null
            : (dimsLength != null ? buildArray((PgArrayList) o, 0, -1) : BooleanTypeUtil.castToBoolean((String) o));
        } else if (pa != null) {
          pa[length++] = o != null && BooleanTypeUtil.castToBoolean(o);
        }
      }
    } else if (type == Types.SMALLINT) {
      short[] pa = null;
      @Nullable Object @Nullable [] oa = null;

      if (dimsLength != null || useObjects) {
        ret =
            oa = (dimsLength != null
                ? (Object[]) java.lang.reflect.Array
                    .newInstance(useObjects ? Short.class : short.class, dimsLength)
                : new Short[count]);
      } else {
        ret = pa = new short[count];
      }

      for (; count > 0; count--) {
        Object o = input.get(index++);

        if (oa != null) {
          oa[length++] = o == null ? null
              : (dimsLength != null ? buildArray((PgArrayList) o, 0, -1) : PgResultSet.toShort((String) o));
        } else if (pa != null) {
          pa[length++] = o == null ? 0 : PgResultSet.toShort((String) o);
        }
      }
    } else if (type == Types.INTEGER) {
      int[] pa = null;
      @Nullable Object @Nullable [] oa = null;

      if (dimsLength != null || useObjects) {
        ret =
            oa = (dimsLength != null
                ? (Object[]) java.lang.reflect.Array
                    .newInstance(useObjects ? Integer.class : int.class, dimsLength)
                : new Integer[count]);
      } else {
        ret = pa = new int[count];
      }

      for (; count > 0; count--) {
        Object o = input.get(index++);

        if (oa != null) {
          oa[length++] = o == null ? null
              : (dimsLength != null ? buildArray((PgArrayList) o, 0, -1) : PgResultSet.toInt((String) o));
        } else if (pa != null) {
          pa[length++] = o == null ? 0 : PgResultSet.toInt((String) o);
        }
      }
    } else if (type == Types.BIGINT) {
      long[] pa = null;
      @Nullable Object @Nullable [] oa = null;

      if (dimsLength != null || useObjects) {
        ret =
            oa = (dimsLength != null
                ? (Object[]) java.lang.reflect.Array
                    .newInstance(useObjects ? Long.class : long.class, dimsLength)
                : new Long[count]);
      } else {
        ret = pa = new long[count];
      }

      for (; count > 0; count--) {
        Object o = input.get(index++);

        if (oa != null) {
          oa[length++] = o == null ? null
              : (dimsLength != null ? buildArray((PgArrayList) o, 0, -1) : PgResultSet.toLong((String) o));
        } else if (pa != null) {
          pa[length++] = o == null ? 0L : PgResultSet.toLong((String) o);
        }
      }
    } else if (type == Types.NUMERIC) {
      @Nullable Object[] oa;
      ret = oa =
          (dimsLength != null ? (Object[]) java.lang.reflect.Array.newInstance(BigDecimal.class, dimsLength)
              : new BigDecimal[count]);

      for (; count > 0; count--) {
        Object v = input.get(index++);
        oa[length++] = dimsLength != null && v != null ? buildArray((PgArrayList) v, 0, -1)
            : (v == null ? null : PgResultSet.toBigDecimal((String) v));
      }
    } else if (type == Types.REAL) {
      float[] pa = null;
      @Nullable Object @Nullable [] oa = null;

      if (dimsLength != null || useObjects) {
        ret =
            oa = (dimsLength != null
                ? (Object[]) java.lang.reflect.Array
                    .newInstance(useObjects ? Float.class : float.class, dimsLength)
                : new Float[count]);
      } else {
        ret = pa = new float[count];
      }

      for (; count > 0; count--) {
        Object o = input.get(index++);

        if (oa != null) {
          oa[length++] = o == null ? null
              : (dimsLength != null ? buildArray((PgArrayList) o, 0, -1) : PgResultSet.toFloat((String) o));
        } else if (pa != null) {
          pa[length++] = o == null ? 0f : PgResultSet.toFloat((String) o);
        }
      }
    } else if (type == Types.DOUBLE) {
      double[] pa = null;
      @Nullable Object[] oa = null;

      if (dimsLength != null || useObjects) {
        ret = oa = (dimsLength != null
            ? (Object[]) java.lang.reflect.Array
                .newInstance(useObjects ? Double.class : double.class, dimsLength)
            : new Double[count]);
      } else {
        ret = pa = new double[count];
      }

      for (; count > 0; count--) {
        Object o = input.get(index++);

        if (oa != null) {
          oa[length++] = o == null ? null
              : (dimsLength != null ? buildArray((PgArrayList) o, 0, -1) : PgResultSet.toDouble((String) o));
        } else if (pa != null) {
          pa[length++] = o == null ? 0d : PgResultSet.toDouble((String) o);
        }
      }
    } else if (type == Types.CHAR || type == Types.VARCHAR || oid == Oid.JSONB_ARRAY) {
      @Nullable Object[] oa;
      ret =
          oa = (dimsLength != null ? (Object[]) java.lang.reflect.Array.newInstance(String.class, dimsLength)
              : new String[count]);

      for (; count > 0; count--) {
        Object v = input.get(index++);
        oa[length++] = dimsLength != null && v != null ? buildArray((PgArrayList) v, 0, -1) : v;
      }
    } else if (type == Types.DATE) {
      @Nullable Object[] oa;
      ret = oa = (dimsLength != null
          ? (Object[]) java.lang.reflect.Array.newInstance(java.sql.Date.class, dimsLength)
          : new java.sql.Date[count]);

      for (; count > 0; count--) {
        Object v = input.get(index++);
        oa[length++] = dimsLength != null && v != null ? buildArray((PgArrayList) v, 0, -1)
            : (v == null ? null : getConnection().getTimestampUtils().toDate(null, (String) v));
      }
    } else if (type == Types.TIME) {
      @Nullable Object[] oa;
      ret = oa = (dimsLength != null
          ? (Object[]) java.lang.reflect.Array.newInstance(java.sql.Time.class, dimsLength)
          : new java.sql.Time[count]);

      for (; count > 0; count--) {
        Object v = input.get(index++);
        oa[length++] = dimsLength != null && v != null ? buildArray((PgArrayList) v, 0, -1)
            : (v == null ? null : getConnection().getTimestampUtils().toTime(null, (String) v));
      }
    } else if (type == Types.TIMESTAMP) {
      @Nullable Object[] oa;
      ret = oa = (dimsLength != null
          ? (Object[]) java.lang.reflect.Array.newInstance(java.sql.Timestamp.class, dimsLength)
          : new java.sql.Timestamp[count]);

      for (; count > 0; count--) {
        Object v = input.get(index++);
        oa[length++] = dimsLength != null && v != null ? buildArray((PgArrayList) v, 0, -1)
            : (v == null ? null : getConnection().getTimestampUtils().toTimestamp(null, (String) v));
      }
    } else if ((type == Types.BINARY || type == Types.VARBINARY) && "bytea".equals(getBaseTypeName())) {
      Object[] oa = null;
      ret = oa = (dims > 1 ? (Object[]) java.lang.reflect.Array.newInstance(byte[].class, dimsLength)
          : new byte[count][]);

      for (; count > 0; count--) {
        Object v = input.get(index++);
        try {
          oa[length++] = dims > 1 && v != null ? buildArray((PgArrayList) v, 0, -1)
              : (v == null ? null : PGbytea.toBytes(((String) v).getBytes("ascii")));
        } catch (UnsupportedEncodingException e) {
          throw new java.lang.Error("ascii must be supported");
        }
      }
    } else if (ArrayAssistantRegistry.getAssistant(oid) != null) {
      ArrayAssistant arrAssistant = castNonNull(ArrayAssistantRegistry.getAssistant(oid));

      @Nullable Object[] oa;
      ret = oa = (dimsLength != null)
          ? (Object[]) java.lang.reflect.Array.newInstance(arrAssistant.baseType(), dimsLength)
          : (Object[]) java.lang.reflect.Array.newInstance(arrAssistant.baseType(), count);

      for (; count > 0; count--) {
        Object v = input.get(index++);
        oa[length++] = (dimsLength != null && v != null) ? buildArray((PgArrayList) v, 0, -1)
            : (v == null ? null : arrAssistant.buildElement((String) v));
      }
    } else if (dims == 1) {
      @Nullable Object[] oa = new Object[count];
      String typeName = getBaseTypeName();
      for (; count > 0; count--) {
        Object v = input.get(index++);
        if (v instanceof String) {
          oa[length++] = getConnection().getObject(typeName, (String) v, null);
        } else if (v instanceof byte[]) {
          oa[length++] = getConnection().getObject(typeName, null, (byte[]) v);
        } else if (v == null) {
          oa[length++] = null;
        } else {
          throw org.postgresql.Driver.notImplemented(this.getClass(), "getArrayImpl(long,int,Map)");
        }
      }
      ret = oa;
    } else {
      // other datatypes not currently supported
      getConnection().getLogger().log(Level.FINEST, "getArrayImpl(long,int,Map) with {0}", getBaseTypeName());

      throw org.postgresql.Driver.notImplemented(this.getClass(), "getArrayImpl(long,int,Map)");
    }

    return ret;
  }

  public int getBaseType() throws SQLException {
    return getConnection().getTypeInfo().getSQLType(getBaseTypeName());
  }

  public String getBaseTypeName() throws SQLException {
    buildArrayList();
    int elementOID = getConnection().getTypeInfo().getPGArrayElement(oid);
    return castNonNull(getConnection().getTypeInfo().getPGType(elementOID));
  }

  public java.sql.ResultSet getResultSet() throws SQLException {
    return getResultSetImpl(1, 0, null);
  }

  public java.sql.ResultSet getResultSet(long index, int count) throws SQLException {
    return getResultSetImpl(index, count, null);
  }

  public ResultSet getResultSet(@Nullable Map<String, Class<?>> map) throws SQLException {
    return getResultSetImpl(map);
  }

  public ResultSet getResultSet(long index, int count, @Nullable Map<String, Class<?>> map)
      throws SQLException {
    return getResultSetImpl(index, count, map);
  }

  public ResultSet getResultSetImpl(@Nullable Map<String, Class<?>> map) throws SQLException {
    return getResultSetImpl(1, 0, map);
  }

  public ResultSet getResultSetImpl(long index, int count, @Nullable Map<String, Class<?>> map)
      throws SQLException {

    // for now maps aren't supported.
    if (map != null && !map.isEmpty()) {
      throw org.postgresql.Driver.notImplemented(this.getClass(), "getResultSetImpl(long,int,Map)");
    }

    // array index is out of range
    if (index < 1) {
      throw new PSQLException(GT.tr("The array index is out of range: {0}", index),
          PSQLState.DATA_ERROR);
    }

    if (fieldBytes != null) {
      return readBinaryResultSet(fieldBytes, (int) index, count);
    }

    PgArrayList arrayList = buildArrayList();

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

    List<Tuple> rows = new ArrayList<Tuple>();

    Field[] fields = new Field[2];

    // one dimensional array
    if (arrayList.dimensionsCount <= 1) {
      // array element type
      final int baseOid = getConnection().getTypeInfo().getPGArrayElement(oid);
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
        t[1] = v == null ? null : getConnection().encodeString(toString((PgArrayList) v));
        rows.add(new Tuple(t));
      }
    }

    BaseStatement stat = (BaseStatement) getConnection()
        .createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    return stat.createDriverResultSet(fields, rows);
  }

  @SuppressWarnings("nullness")
  public @Nullable String toString() {
    if (fieldString == null && fieldBytes != null) {
      try {
        Object array = readBinaryArray(fieldBytes, 1, 0);

        final Arrays.ArraySupport arraySupport = Arrays.getArraySupport(array);
        assert arraySupport != null;
        fieldString = arraySupport.toArrayString(connection.getTypeInfo().getArrayDelimiter(oid), array);
      } catch (SQLException e) {
        fieldString = "NULL"; // punt
      }
    }
    return fieldString;
  }

  /**
   * Convert array list to PG String representation (e.g. {0,1,2}).
   */
  private String toString(PgArrayList list) throws SQLException {
    if (list == null) {
      return "NULL";
    }

    StringBuilder b = new StringBuilder().append('{');

    char delim = getConnection().getTypeInfo().getArrayDelimiter(oid);

    for (int i = 0; i < list.size(); i++) {
      Object v = list.get(i);

      if (i > 0) {
        b.append(delim);
      }

      if (v == null) {
        b.append("NULL");
      } else if (v instanceof PgArrayList) {
        b.append(toString((PgArrayList) v));
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

  public byte @Nullable [] toBytes() {
    return fieldBytes;
  }

  public void free() throws SQLException {
    connection = null;
    fieldString = null;
    fieldBytes = null;
    arrayList = null;
  }
}
