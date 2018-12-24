/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.Encoding;
import org.postgresql.core.Field;
import org.postgresql.core.Oid;
import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc2.ArrayAssistant;
import org.postgresql.jdbc2.ArrayAssistantRegistry;
import org.postgresql.udt.BoolValueAccess;
import org.postgresql.udt.Float4ValueAccess;
import org.postgresql.udt.Float8ValueAccess;
import org.postgresql.udt.Int2ValueAccess;
import org.postgresql.udt.Int4ValueAccess;
import org.postgresql.udt.Int8ValueAccess;
import org.postgresql.udt.SingleAttributeSQLInputHelper;
import org.postgresql.udt.TextValueAccess;
import org.postgresql.udt.UdtMap;
import org.postgresql.udt.ValueAccess;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.IOException;
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
  private static class PgArrayList extends ArrayList<Object> {

    private static final long serialVersionUID = 2052783752654562677L;

    /**
     * How many dimensions.
     */
    int dimensionsCount = 1;

  }

  /**
   * A database connection.
   */
  protected BaseConnection connection = null;

  /**
   * The OID of this field.
   */
  private int oid;

  /**
   * Field value as String.
   */
  protected String fieldString = null;

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
  protected PgArrayList arrayList;

  protected byte[] fieldBytes;

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
  public PgArray(BaseConnection connection, int oid, String fieldString) throws SQLException {
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
  public PgArray(BaseConnection connection, int oid, byte[] fieldBytes) throws SQLException {
    this(connection, oid);
    this.fieldBytes = fieldBytes;
  }

  @Override
  public Object getArray() throws SQLException {
    return getArrayImpl(1, 0, connection.getUdtMap());
  }

  @Override
  public Object getArray(long index, int count) throws SQLException {
    // TODO: count == 0 here would cause all elements to be retrieved.  Is this part of the specification
    //       or an unintended side-effect?
    return getArrayImpl(index, count, connection.getUdtMap());
  }

  @Override
  public Object getArray(Map<String, Class<?>> map) throws SQLException {
    return getArrayImpl(1, 0,
        (map != null) ? new UdtMap(map) : UdtMap.EMPTY);
  }

  @Override
  public Object getArray(long index, int count, Map<String, Class<?>> map) throws SQLException {
    // TODO: count == 0 here would cause all elements to be retrieved.  Is this part of the specification
    //       or an unintended side-effect?
    return getArrayImpl(index, count,
        (map != null) ? new UdtMap(map) : UdtMap.EMPTY);
  }

  public Object getArrayImpl(long index, int count, UdtMap udtMap) throws SQLException {
    // TODO: What to do if count < 0?

    // array index is out of range
    if (index < 1) {
      throw new PSQLException(GT.tr("The array index is out of range: {0}", index),
          PSQLState.DATA_ERROR);
    }

    if (fieldBytes != null) {
      if (index > Integer.MAX_VALUE) {
        throw new PSQLException(GT.tr("The array index is out of range: {0}", index),
            PSQLState.DATA_ERROR);
      }
      return readBinaryArray((int) index, count, udtMap);
    }

    if (fieldString == null) {
      return null;
    }

    buildArrayList();

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

    assert index <= Integer.MAX_VALUE : "This cast to int is safe because it must be <= arrayList.size()";
    return buildArray(arrayList, (int) index, count, udtMap);
  }

  private Object readBinaryArray(int index, int count, UdtMap udtMap) throws SQLException {
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
    TypeInfo typeInfo = connection.getTypeInfo();
    String pgType = typeInfo.getPGType(elementOid);
    Class<?> customType = udtMap.getTypeMap().get(pgType);
    if (dimensions == 0) {
      return java.lang.reflect.Array.newInstance(customType != null ? customType : elementOidToClass(elementOid), 0);
    }
    if (count > 0) {
      // TODO: If allowing count == 0 to mean no elements, not all elements, this would be affected
      dims[0] = Math.min(count, dims[0]);
    }
    final int sqlType = typeInfo.getSQLType(elementOid);
    // TODO: Where is the array created for the last dimension?
    //       I can't find it to make sure we're using custom types on it.
    //       Is there a new test case for something broken here?
    Object arr = java.lang.reflect.Array.newInstance(customType != null ? customType : elementOidToClass(elementOid), dims);
    try {
      storeValues((Object[]) arr, elementOid, pgType, sqlType, dims, pos, 0, index, udtMap, customType);
    } catch (IOException ioe) {
      throw new PSQLException(
          GT.tr(
              "Invalid character data was found.  This is most likely caused by stored data containing characters that are invalid for the character set the database was created in.  The most common example of this is storing 8bit data in a SQL_ASCII database."),
          PSQLState.DATA_ERROR, ioe);
    }
    return arr;
  }

  // TODO: Is pgType still used by our final implementation?
  private int storeValues(final Object[] arr, int elementOid, String pgType, int sqlType, final int[] dims, int pos,
      final int thisDimension, int index, UdtMap udtMap, Class<?> customType) throws SQLException, IOException {
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
          continue;
        }
        if (customType == null) {
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
            // TODO: More types here?
            // TODO: PGobject here?
            default:
              ArrayAssistant arrAssistant = ArrayAssistantRegistry.getAssistant(elementOid);
              if (arrAssistant != null) {
                arr[i] = arrAssistant.buildElement(fieldBytes, pos, len);
              } else {
                // TODO: Why is arr[i] left as null when no assistant?
                // TODO: Would this be a place for an exception or an AssertionError?
              }
          }
        } else {
          final ValueAccess access;
          switch (elementOid) {
            case Oid.INT2:
              access = new Int2ValueAccess(connection,
                  ByteConverter.int2(fieldBytes, pos), udtMap);
              break;
            case Oid.INT4:
              access = new Int4ValueAccess(connection,
                  ByteConverter.int4(fieldBytes, pos), udtMap);
              break;
            case Oid.INT8:
              access = new Int8ValueAccess(connection,
                  ByteConverter.int8(fieldBytes, pos), udtMap);
              break;
            case Oid.FLOAT4:
              access = new Float4ValueAccess(connection,
                  ByteConverter.float4(fieldBytes, pos), udtMap);
              break;
            case Oid.FLOAT8:
              access = new Float8ValueAccess(connection,
                  ByteConverter.float8(fieldBytes, pos), udtMap);
              break;
            case Oid.TEXT:
            case Oid.VARCHAR:
              Encoding encoding = connection.getEncoding();
              access = new TextValueAccess(connection, elementOid,
                  encoding.decode(fieldBytes, pos, len), udtMap);
              break;
            case Oid.BOOL:
              access = new BoolValueAccess(connection,
                  ByteConverter.bool(fieldBytes, pos), udtMap);
              break;
            // TODO: More types here?
            // TODO: PGobject here?
            default:
              ArrayAssistant arrAssistant = ArrayAssistantRegistry.getAssistant(elementOid);
              if (arrAssistant != null) {
                access = arrAssistant.getValueAccess(connection, elementOid,
                    arrAssistant.buildElement(fieldBytes, pos, len), udtMap);
              } else {
                // TODO: Why is arr[i] left as null when no assistant?
                // TODO: Would this be a place for an exception or an AssertionError?
                access = null;
              }
          }
          if (access != null) {
            arr[i] = SingleAttributeSQLInputHelper.getObjectCustomType(
                udtMap, pgType, customType, access.getSQLInput(udtMap));
          }
        }
        pos += len;
      }
    } else {
      for (int i = 0; i < dims[thisDimension]; ++i) {
        pos = storeValues((Object[]) arr[i], elementOid, pgType, sqlType, dims, pos, thisDimension + 1, 0, udtMap, customType);
      }
    }
    return pos;
  }


  private ResultSet readBinaryResultSet(int index, int count, UdtMap udtMap) throws SQLException {
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
      // TODO: If allowing count == 0 to mean no elements, not all elements, this would be affected
      dims[0] = Math.min(count, dims[0]);
    }
    List<byte[][]> rows = new ArrayList<byte[][]>();
    Field[] fields = new Field[2];

    storeValues(rows, fields, elementOid, dims, pos, 0, index);

    BaseStatement stat = (BaseStatement) connection
        .createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    return stat.createDriverResultSet(fields, rows, udtMap);
  }

  private int storeValues(List<byte[][]> rows, Field[] fields, int elementOid, final int[] dims,
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
        // TODO: Beware 32-bit index:
        ByteConverter.int4(rowData[0], 0, i + index);
        rows.add(rowData);
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
        pos = calcRemainingDataLength(dims, pos, elementOid, nextDimension);
      }
      for (int i = 0; i < dims[thisDimension]; ++i) {
        byte[][] rowData = new byte[2][];
        rowData[0] = new byte[4];
        // TODO: Beware 32-bit index:
        ByteConverter.int4(rowData[0], 0, i + index);
        rows.add(rowData);
        int dataEndPos = calcRemainingDataLength(dims, pos, elementOid, nextDimension);
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

  private int calcRemainingDataLength(int[] dims, int pos, int elementOid, int thisDimension) {
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
      pos = calcRemainingDataLength(dims, elementOid, pos, thisDimension + 1);
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
      case Oid.TEXT:
      case Oid.VARCHAR:
        return String.class;
      case Oid.BOOL:
        return Boolean.class;
      // TODO: More types here?
      // TODO: PGobject here?
      default:
        ArrayAssistant arrAssistant = ArrayAssistantRegistry.getAssistant(oid);
        if (arrAssistant != null) {
          return arrAssistant.baseType();
        }

        throw org.postgresql.Driver.notImplemented(this.getClass(), "readBinaryArray(data,oid)");
    }
  }

  /**
   * Build {@link ArrayList} from field's string input. As a result of this method
   * {@link #arrayList} is build. Method can be called many times in order to make sure that array
   * list is ready to use, however {@link #arrayList} will be set only once during first call.
   */
  private synchronized void buildArrayList() throws SQLException {
    if (arrayList != null) {
      return;
    }

    arrayList = new PgArrayList();

    char delim = connection.getTypeInfo().getArrayDelimiter(oid);

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
  }

  /**
   * Convert {@link ArrayList} to array.
   *
   * @param input list to be converted into array
   */
  private Object buildArray(PgArrayList input, int index, int count, UdtMap udtMap) throws SQLException {
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
    if (dims > 1) {
      for (int i = 0; i < dims; i++) {
        dimsLength[i] = (i == 0 ? count : 0);
      }
    }

    // array elements counter
    int length = 0;

    // array elements type
    TypeInfo typeInfo = connection.getTypeInfo();
    int elementOID = typeInfo.getPGArrayElement(oid);
    final int sqlType = typeInfo.getSQLType(elementOID);
    String pgType = typeInfo.getPGType(sqlType);
    Class<?> customType = udtMap.getTypeMap().get(pgType);

    if (customType != null) {
      if (dims > 1) {
        Object[] oa;
        ret = oa = (Object[]) java.lang.reflect.Array.newInstance(customType, dimsLength);
        for (; count > 0; count--) {
          // TODO: Beware 32-bit index:
          Object v = input.get(index++);
          oa[length++] = (v != null) ? buildArray((PgArrayList) v, 0, -1, udtMap) : null;
        }
      } else {
        Object[] oa;
        ret = oa = (Object[]) java.lang.reflect.Array.newInstance(customType, count);
        // TODO: ArrayAssistantRegistry is accessed haphazardly by the array OID or the element OID.
        // TODO: In the case of UUID, both are in the registry, but this inconsistency is unexpected.
        ArrayAssistant arrAssistant = ArrayAssistantRegistry.getAssistant(oid);
        if (arrAssistant != null) {
          // Use array assistant
          for (; count > 0; count--) {
            // TODO: Beware 32-bit index:
            Object v = input.get(index++);
            if (v != null) {
              ValueAccess access = arrAssistant.getValueAccess(connection, oid, // TODO: elementOID here?
                  arrAssistant.buildElement((String) v), udtMap);
              oa[length++] = SingleAttributeSQLInputHelper.getObjectCustomType(
                  udtMap, pgType, customType, access.getSQLInput(udtMap));
            } else {
              // oa[length] is already null
              length++;
            }
          }
        } else {
          // Since we have a string value at this point, all custom objects are processed
          // via StringValueAccess, which performs conversions compatible with PgResultSet

          // TODO: Ultimately we'd like to eliminate this redundancy betwen PgResultSet and TextValueAccess,
          //       and instead have PgResultSet hold a set of ValueAccess objects, which would do the conversions
          //       from byte[] and String.  But we fear if we change too much core implementation this pull request
          //       would less likely be merged.
          for (; count > 0; count--) {
            // TODO: Beware 32-bit index:
            Object v = input.get(index++);
            if (v != null) {
              ValueAccess access = new TextValueAccess(connection, elementOID, (String) v, udtMap);
              oa[length++] = SingleAttributeSQLInputHelper.getObjectCustomType(
                  udtMap, pgType, customType, access.getSQLInput(udtMap));
            } else {
              // oa[length] is already null
              length++;
            }
          }
        }
      }
    } else if (sqlType == Types.BIT) {
      boolean[] pa = null; // primitive array
      Object[] oa = null; // objects array

      if (dims > 1 || useObjects) {
        ret = oa = (dims > 1
            ? (Object[]) java.lang.reflect.Array
                .newInstance(useObjects ? Boolean.class : boolean.class, dimsLength)
            : new Boolean[count]);
      } else {
        ret = pa = new boolean[count];
      }

      // add elements
      for (; count > 0; count--) {
        // TODO: Beware 32-bit index:
        Object o = input.get(index++);

        if (dims > 1 || useObjects) {
          oa[length++] = o == null ? null
            : (dims > 1 ? buildArray((PgArrayList) o, 0, -1, udtMap) : BooleanTypeUtil.fromString((String) o));
        } else {
          pa[length++] = o == null ? false : BooleanTypeUtil.fromString((String) o);
        }
      }
    } else if (sqlType == Types.SMALLINT) {
      short[] pa = null;
      Object[] oa = null;

      if (dims > 1 || useObjects) {
        ret =
            oa = (dims > 1
                ? (Object[]) java.lang.reflect.Array
                    .newInstance(useObjects ? Short.class : short.class, dimsLength)
                : new Short[count]);
      } else {
        ret = pa = new short[count];
      }

      for (; count > 0; count--) {
        // TODO: Beware 32-bit index:
        Object o = input.get(index++);

        if (dims > 1 || useObjects) {
          oa[length++] = o == null ? null
              : (dims > 1 ? buildArray((PgArrayList) o, 0, -1, udtMap) : PgResultSet.toShort((String) o));
        } else {
          pa[length++] = o == null ? 0 : PgResultSet.toShort((String) o);
        }
      }
    } else if (sqlType == Types.INTEGER) {
      int[] pa = null;
      Object[] oa = null;

      if (dims > 1 || useObjects) {
        ret =
            oa = (dims > 1
                ? (Object[]) java.lang.reflect.Array
                    .newInstance(useObjects ? Integer.class : int.class, dimsLength)
                : new Integer[count]);
      } else {
        ret = pa = new int[count];
      }

      for (; count > 0; count--) {
        // TODO: Beware 32-bit index:
        Object o = input.get(index++);

        if (dims > 1 || useObjects) {
          oa[length++] = o == null ? null
              : (dims > 1 ? buildArray((PgArrayList) o, 0, -1, udtMap) : PgResultSet.toInt((String) o));
        } else {
          pa[length++] = o == null ? 0 : PgResultSet.toInt((String) o);
        }
      }
    } else if (sqlType == Types.BIGINT) {
      long[] pa = null;
      Object[] oa = null;

      if (dims > 1 || useObjects) {
        ret =
            oa = (dims > 1
                ? (Object[]) java.lang.reflect.Array
                    .newInstance(useObjects ? Long.class : long.class, dimsLength)
                : new Long[count]);
      } else {
        ret = pa = new long[count];
      }

      for (; count > 0; count--) {
        // TODO: Beware 32-bit index:
        Object o = input.get(index++);

        if (dims > 1 || useObjects) {
          oa[length++] = o == null ? null
              : (dims > 1 ? buildArray((PgArrayList) o, 0, -1, udtMap) : PgResultSet.toLong((String) o));
        } else {
          pa[length++] = o == null ? 0L : PgResultSet.toLong((String) o);
        }
      }
    } else if (sqlType == Types.NUMERIC) {
      Object[] oa = null;
      ret = oa =
          (dims > 1 ? (Object[]) java.lang.reflect.Array.newInstance(BigDecimal.class, dimsLength)
              : new BigDecimal[count]);

      for (; count > 0; count--) {
        // TODO: Beware 32-bit index:
        Object v = input.get(index++);
        oa[length++] = dims > 1 && v != null ? buildArray((PgArrayList) v, 0, -1, udtMap)
            : (v == null ? null : PgResultSet.toBigDecimal((String) v));
      }
    } else if (sqlType == Types.REAL) {
      float[] pa = null;
      Object[] oa = null;

      if (dims > 1 || useObjects) {
        ret =
            oa = (dims > 1
                ? (Object[]) java.lang.reflect.Array
                    .newInstance(useObjects ? Float.class : float.class, dimsLength)
                : new Float[count]);
      } else {
        ret = pa = new float[count];
      }

      for (; count > 0; count--) {
        // TODO: Beware 32-bit index:
        Object o = input.get(index++);

        if (dims > 1 || useObjects) {
          oa[length++] = o == null ? null
              : (dims > 1 ? buildArray((PgArrayList) o, 0, -1, udtMap) : PgResultSet.toFloat((String) o));
        } else {
          pa[length++] = o == null ? 0f : PgResultSet.toFloat((String) o);
        }
      }
    } else if (sqlType == Types.DOUBLE) {
      double[] pa = null;
      Object[] oa = null;

      if (dims > 1 || useObjects) {
        ret = oa = (dims > 1
            ? (Object[]) java.lang.reflect.Array
                .newInstance(useObjects ? Double.class : double.class, dimsLength)
            : new Double[count]);
      } else {
        ret = pa = new double[count];
      }

      for (; count > 0; count--) {
        // TODO: Beware 32-bit index:
        Object o = input.get(index++);

        if (dims > 1 || useObjects) {
          oa[length++] = o == null ? null
              : (dims > 1 ? buildArray((PgArrayList) o, 0, -1, udtMap) : PgResultSet.toDouble((String) o));
        } else {
          pa[length++] = o == null ? 0d : PgResultSet.toDouble((String) o);
        }
      }
    } else if (sqlType == Types.CHAR || sqlType == Types.VARCHAR || oid == Oid.JSONB_ARRAY) {
      Object[] oa = null;
      ret =
          oa = (dims > 1 ? (Object[]) java.lang.reflect.Array.newInstance(String.class, dimsLength)
              : new String[count]);

      for (; count > 0; count--) {
        // TODO: Beware 32-bit index:
        Object v = input.get(index++);
        oa[length++] = dims > 1 && v != null ? buildArray((PgArrayList) v, 0, -1, udtMap) : v;
      }
    } else if (sqlType == Types.DATE) {
      Object[] oa = null;
      ret = oa = (dims > 1
          ? (Object[]) java.lang.reflect.Array.newInstance(java.sql.Date.class, dimsLength)
          : new java.sql.Date[count]);

      for (; count > 0; count--) {
        // TODO: Beware 32-bit index:
        Object v = input.get(index++);
        oa[length++] = dims > 1 && v != null ? buildArray((PgArrayList) v, 0, -1, udtMap)
            : (v == null ? null : connection.getTimestampUtils().toDate(null, (String) v));
      }
    } else if (sqlType == Types.TIME) {
      Object[] oa = null;
      ret = oa = (dims > 1
          ? (Object[]) java.lang.reflect.Array.newInstance(java.sql.Time.class, dimsLength)
          : new java.sql.Time[count]);

      for (; count > 0; count--) {
        // TODO: Beware 32-bit index:
        Object v = input.get(index++);
        oa[length++] = dims > 1 && v != null ? buildArray((PgArrayList) v, 0, -1, udtMap)
            : (v == null ? null : connection.getTimestampUtils().toTime(null, (String) v));
      }
    } else if (sqlType == Types.TIMESTAMP) {
      Object[] oa = null;
      ret = oa = (dims > 1
          ? (Object[]) java.lang.reflect.Array.newInstance(java.sql.Timestamp.class, dimsLength)
          : new java.sql.Timestamp[count]);

      for (; count > 0; count--) {
        // TODO: Beware 32-bit index:
        Object v = input.get(index++);
        oa[length++] = dims > 1 && v != null ? buildArray((PgArrayList) v, 0, -1, udtMap)
            : (v == null ? null : connection.getTimestampUtils().toTimestamp(null, (String) v));
      }
    } else {
      ArrayAssistant arrAssistant = ArrayAssistantRegistry.getAssistant(oid);
      if (arrAssistant != null) {

        Object[] oa = null;
        ret = oa = (dims > 1)
            ? (Object[]) java.lang.reflect.Array.newInstance(arrAssistant.baseType(), dimsLength)
            : (Object[]) java.lang.reflect.Array.newInstance(arrAssistant.baseType(), count);

        for (; count > 0; count--) {
          // TODO: Beware 32-bit index:
          Object v = input.get(index++);
          oa[length++] = (dims > 1 && v != null) ? buildArray((PgArrayList) v, 0, -1, udtMap)
              : (v == null ? null : arrAssistant.buildElement((String) v));
        }
      } else if (dims == 1) {
        Object[] oa = new Object[count];
        for (; count > 0; count--) {
          // TODO: Beware 32-bit index:
          Object v = input.get(index++);
          if (v instanceof String) {
            oa[length++] = connection.getObject(pgType, (String) v, null);
          } else if (v instanceof byte[]) {
            // TODO: is v ever a byte[] here?  It's assumes String in all the above
            oa[length++] = connection.getObject(pgType, null, (byte[]) v);
          } else if (v == null) {
            oa[length++] = null;
          } else {
            throw org.postgresql.Driver.notImplemented(this.getClass(), "getArrayImpl(long,int,Map)");
          }
        }
        ret = oa;
      } else {
        // other datatypes not currently supported
        connection.getLogger().log(Level.FINEST, "getArrayImpl(long,int,Map) with {0}", pgType);

        throw org.postgresql.Driver.notImplemented(this.getClass(), "getArrayImpl(long,int,Map)");
      }
    }

    return ret;
  }

  public int getBaseType() throws SQLException {
    return connection.getTypeInfo().getSQLType(getBaseTypeName());
  }

  @Override
  public String getBaseTypeName() throws SQLException {
    // TODO: Would this in any way be affected by custom type map?
    // TODO: Including if a base type is provided via PgCallableStatement?
    // TODO: If it is affected, other places that assume TypeInfo.getPGType(...)
    //       would probably need to call this method instead
    buildArrayList();
    TypeInfo typeInfo = connection.getTypeInfo();
    int elementOID = typeInfo.getPGArrayElement(oid);
    return typeInfo.getPGType(elementOID);
  }

  @Override
  public java.sql.ResultSet getResultSet() throws SQLException {
    return getResultSetImpl(1, 0, connection.getUdtMap());
  }

  @Override
  public java.sql.ResultSet getResultSet(long index, int count) throws SQLException {
    // TODO: count == 0 here would cause all elements to be retrieved.  Is this part of the specification
    //       or an unintended side-effect?
    return getResultSetImpl(index, count, connection.getUdtMap());
  }

  @Override
  public ResultSet getResultSet(Map<String, Class<?>> map) throws SQLException {
    return getResultSetImpl(1, 0,
        (map != null) ? new UdtMap(map) : UdtMap.EMPTY);
  }

  @Override
  public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map)
      throws SQLException {
    // TODO: count == 0 here would cause all elements to be retrieved.  Is this part of the specification
    //       or an unintended side-effect?
    return getResultSetImpl(index, count,
        (map != null) ? new UdtMap(map) : UdtMap.EMPTY);
  }

  public ResultSet getResultSetImpl(long index, int count, UdtMap udtMap)
      throws SQLException {
    // TODO: What to do if count < 0?

    // array index is out of range
    if (index < 1) {
      throw new PSQLException(GT.tr("The array index is out of range: {0}", index),
          PSQLState.DATA_ERROR);
    }

    if (fieldBytes != null) {
      if (index > Integer.MAX_VALUE) {
        throw new PSQLException(GT.tr("The array index is out of range: {0}", index),
            PSQLState.DATA_ERROR);
      }
      return readBinaryResultSet((int) index, count, udtMap);
    }

    buildArrayList();

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

    List<byte[][]> rows = new ArrayList<byte[][]>();

    Field[] fields = new Field[2];

    // one dimensional array
    if (arrayList.dimensionsCount <= 1) {
      // array element type
      final int baseOid = connection.getTypeInfo().getPGArrayElement(oid);
      fields[0] = new Field("INDEX", Oid.INT4);
      fields[1] = new Field("VALUE", baseOid);

      for (int i = 0; i < count; i++) {
        assert index <= Integer.MAX_VALUE : "This cast to int is safe because it must be <= arrayList.size()";
        // TODO: Beware 32-bit index:
        int offset = (int) index + i;
        byte[][] t = new byte[2][0];
        String v = (String) arrayList.get(offset);
        t[0] = connection.encodeString(Integer.toString(offset + 1));
        t[1] = v == null ? null : connection.encodeString(v);
        rows.add(t);
      }
    } else {
      // when multi-dimensional
      fields[0] = new Field("INDEX", Oid.INT4);
      fields[1] = new Field("VALUE", oid);
      for (int i = 0; i < count; i++) {
        assert index <= Integer.MAX_VALUE : "This cast to int is safe because it must be <= arrayList.size()";
        // TODO: Beware 32-bit index:
        int offset = (int) index + i;
        byte[][] t = new byte[2][0];
        Object v = arrayList.get(offset);

        t[0] = connection.encodeString(Integer.toString(offset + 1));
        t[1] = v == null ? null : connection.encodeString(toString((PgArrayList) v));
        rows.add(t);
      }
    }

    BaseStatement stat = (BaseStatement) connection
        .createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    return stat.createDriverResultSet(fields, rows, udtMap);
  }

  @Override
  public String toString() {
    // TODO: toString() will return null when both fieldString == null and fieldBytes == null, is this possible to happen?
    if (fieldString == null && fieldBytes != null) {
      try {
        Object array = readBinaryArray(1, 0, connection.getUdtMap());

        final PrimitiveArraySupport arraySupport = PrimitiveArraySupport.getArraySupport(array);
        if (arraySupport != null) {
          fieldString = arraySupport.toArrayString(connection.getTypeInfo().getArrayDelimiter(oid), array);
        } else {
          java.sql.Array tmpArray = connection.createArrayOf(getBaseTypeName(), (Object[]) array);
          fieldString = tmpArray.toString();
        }
      } catch (SQLException e) {
        // TODO: Swallowing exceptions is not cool.  At least log this...
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

    char delim = connection.getTypeInfo().getArrayDelimiter(oid);

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

  public byte[] toBytes() {
    return fieldBytes;
  }

  public void free() throws SQLException {
    connection = null;
    fieldString = null;
    fieldBytes = null;
    arrayList = null;
  }
}
