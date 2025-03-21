/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import org.postgresql.Driver;
import org.postgresql.core.BaseConnection;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;
import java.util.function.Function;

public class PgSQLInput implements SQLInput {
  private static final SQLFunction<String, String> stringConv = (value) -> value;
  private static final SQLFunction<String, Byte> byteConv = (value) -> Byte.valueOf(value);
  private static final SQLFunction<String, Short> shortConv = (value) -> Short.valueOf(value);
  private static final SQLFunction<String, Integer> intConv = (value) -> Integer.valueOf(value);
  private static final SQLFunction<String, Long> longConv = (value) -> Long.valueOf(value);
  private static final SQLFunction<String, Float> floatConv = (value) -> Float.valueOf(value);
  private static final SQLFunction<String, Double> doubleConv = (value) -> Double.valueOf(value);
  private static final SQLFunction<String, BigDecimal> bigDecimalConv = (value) -> new BigDecimal(value);
  private static final SQLFunction<String, BigInteger> bigIntConv = (value) -> new BigInteger(value);
  private static final SQLFunction<String, byte[]> bytesConv = (value) -> value.getBytes(UTF_8);
  private static final SQLFunction<String, Boolean> boolConv = (value) -> {
    if ("t".equals(value)) {
      return Boolean.TRUE;
    }
    return Boolean.FALSE;
  };
  private static final SQLFunction<String, URL> urlConv = (value) -> {
    try {
      return new URL(value);
    } catch (MalformedURLException ex) {
      throw new SQLException(ex);
    }
  };
  private static final Function<TimestampUtils, SQLFunction<String, Timestamp>> timestampConvFn =
      (timestampUtils) -> (value) -> timestampUtils.toTimestamp(null, value.getBytes(UTF_8));
  private static final Function<TimestampUtils, SQLFunction<String, Time>> timeConvFn =
      (timestampUtils) -> (value) -> timestampUtils.toTime(null, value.getBytes(UTF_8));
  private static final Function<TimestampUtils, SQLFunction<String, Date>> dateConvFn =
      (timestampUtils) -> (value) -> timestampUtils.toDate(null, value.getBytes(UTF_8));
  private static final Function<BaseConnection, SQLFunction<String, Array>> arrayConvFn =
      (connection) ->  (value) -> {
        // return new PgArray(connection, Oid.TEXT, value);
        throw Driver.notImplemented(PgSQLInput.class, "readArray()");
      };

  private final SQLFunction<String, Timestamp> timestampConv;
  private final SQLFunction<String, Time> timeConv;
  private final SQLFunction<String, Date> dateConv;
  private final SQLFunction<String, Array> arrayConv;

  private int index = -1;
  private @Nullable Boolean wasNull = null;
  private final List<@Nullable String> values;
  private BaseConnection connection;
  private TimestampUtils timestampUtils;

  public PgSQLInput(List<@Nullable String> values, BaseConnection connection, TimestampUtils timestampUtils) {
    this.values = values;
    this.connection = connection;
    this.timestampUtils = timestampUtils;

    timestampConv = timestampConvFn.apply(timestampUtils);
    timeConv = timeConvFn.apply(timestampUtils);
    dateConv = dateConvFn.apply(timestampUtils);
    arrayConv = arrayConvFn.apply(connection);
  }

  private @Nullable <T> T getNextValue(SQLFunction<String, T> convert) throws SQLException {
    index++;

    String value = values.get(index);
    if (value == null) {
      wasNull = true;
      return null;
    }

    T result = convert.apply(value);
    wasNull = result == null;
    return result;
  }

  @SuppressWarnings("override.return")
  @Override
  public @Nullable String readString() throws SQLException {
    return getNextValue(stringConv);
  }

  @Override
  public boolean readBoolean() throws SQLException {
    Boolean result = getNextValue(boolConv);
    return result == null ? false : result;
  }

  @Override
  public byte readByte() throws SQLException {
    Byte result = getNextValue(byteConv);
    return result == null ? 0 : result;
  }

  @Override
  public short readShort() throws SQLException {
    Short result = getNextValue(shortConv);
    return result == null ? 0 : result;
  }

  @Override
  public int readInt() throws SQLException {
    Integer result = getNextValue(intConv);
    return result == null ? 0 : result;
  }

  @Override
  public long readLong() throws SQLException {
    Long result = getNextValue(longConv);
    return result == null ? 0 : result;
  }

  @Override
  public float readFloat() throws SQLException {
    Float result = getNextValue(floatConv);
    return result == null ? 0 : result;
  }

  @Override
  public double readDouble() throws SQLException {
    Double result = getNextValue(doubleConv);
    return result == null ? 0 : result;
  }

  @SuppressWarnings("override.return")
  @Override
  public @Nullable BigDecimal readBigDecimal() throws SQLException {
    return getNextValue(bigDecimalConv);
  }

  @SuppressWarnings({"override.return", "nullness.on.primitive", "return"})
  @Override
  public byte @Nullable [] readBytes() throws SQLException {
    return getNextValue(bytesConv);
  }

  @SuppressWarnings("override.return")
  @Override
  public @Nullable Date readDate() throws SQLException {
    return getNextValue(dateConv);
  }

  @SuppressWarnings("override.return")
  @Override
  public @Nullable Time readTime() throws SQLException {
    return getNextValue(timeConv);
  }

  @SuppressWarnings("override.return")
  @Override
  public @Nullable Timestamp readTimestamp() throws SQLException {
    return getNextValue(timestampConv);
  }

  @Override
  public Reader readCharacterStream() throws SQLException {
    @Nullable String data = readString();
    return new StringReader(data == null ? "" : data);
  }

  @Override
  public InputStream readAsciiStream() throws SQLException {
    String result = readString();
    return new ByteArrayInputStream(result == null ? new byte[0] : result.getBytes(US_ASCII));
  }

  @Override
  public InputStream readBinaryStream() throws SQLException {
    byte[] bytes = readBytes();
    return new ByteArrayInputStream(bytes == null ? new byte[0] : bytes);
  }

  @SuppressWarnings("override.return")
  @Override
  public @Nullable Object readObject() throws SQLException {
    return getNextValue(stringConv);
  }

  @SuppressWarnings("override.return")
  @Override
  public @Nullable <T> T readObject(Class<T> type) throws SQLException {
    return getNextValue(getConverter(type, connection, timestampUtils));
  }

  @Override
  public Ref readRef() throws SQLException {
    throw Driver.notImplemented(this.getClass(), "readRef()");
  }

  @Override
  public Blob readBlob() throws SQLException {
    throw Driver.notImplemented(this.getClass(), "readBlob()");
  }

  @Override
  public Clob readClob() throws SQLException {
    throw Driver.notImplemented(this.getClass(), "readClob()");
  }

  @SuppressWarnings("override.return")
  @Override
  public @Nullable Array readArray() throws SQLException {
    return getNextValue(arrayConv);
  }

  @SuppressWarnings("override.return")
  @Override
  public @Nullable URL readURL() throws SQLException {
    return getNextValue(urlConv);
  }

  @Override
  public SQLXML readSQLXML() throws SQLException {
    return new PgSQLXML(connection, readString());
  }

  @Override
  public RowId readRowId() throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getRowId()");
  }

  @Override
  public boolean wasNull() throws SQLException {
    return wasNull == null ? false : wasNull;
  }

  @Override
  public NClob readNClob() throws SQLException {
    throw Driver.notImplemented(this.getClass(), "readNClob()");
  }

  @Override
  public String readNString() throws SQLException {
    throw Driver.notImplemented(this.getClass(), "readNString()");
  }

  //
  // NOTE: Without the @SuppressWarnings("nullness")
  //   error: [argument] incompatible argument for parameter arg0 of Array.set.
  //       java.lang.reflect.Array.set(results, i, converter.apply(item));
  //                                   ^
  //   found   : @Initialized @NonNull Object @Initialized @Nullable []
  //   required: @Initialized @NonNull Object
  //
  @SuppressWarnings("nullness")
  private static Object reflectArray(Class<?> itemType, SQLFunction<String, ?> converter, String value) throws SQLException {
    List<@Nullable String> items = new SQLDataReader().parseArray(value);
    Object @Nullable [] results = (Object @Nullable []) java.lang.reflect.Array.newInstance(itemType, items.size());
    for (int i = 0; i < items.size(); i++) {
      @Nullable String item = items.get(i);
      if ("NULL".equals(item)) {
        java.lang.reflect.Array.set(results, i, null);
      } else {
        java.lang.reflect.Array.set(results, i, converter.apply(item));
      }
    }
    return results;
  }

  public static <T> T readGenericArray(String value, Class<T> type, BaseConnection connection, TimestampUtils timestampUtils) throws SQLException {
    if (type == null) {
      throw new SQLException("type is null");
    }
    Class<?> itemType = type.getComponentType();
    if (itemType == null) {
      throw new SQLException("Array item type is null");
    }
    SQLFunction<String, ?> converter = getConverter(itemType, connection, timestampUtils);
    // return type.cast(buildArray(itemType, converter, value));
    return type.cast(reflectArray(itemType, converter, value));
  }

  private static <T> SQLFunction<String, T> getConverter(Class<T> type, BaseConnection connection, TimestampUtils timestampUtils) throws SQLException {
    if (type.isArray()) {
      return (value) -> readGenericArray(value, type, connection, timestampUtils);
    }

    if (SQLData.class.isAssignableFrom(type)) {
      return (value) -> {
        //
        // NOTE: This method can return null but I think the converters are all called after a null check
        // and thus are not configured to return null because the null is handled prior to this call.
        // But since this is used elsewhere where null is a valid result I'm just going to throw an error
        // if we get a null result here to make the CheckerFramework happy.
        //
        T result = new SQLDataReader().read(value, type, connection, timestampUtils);
        if (result == null) {
          throw new SQLException("Null value found.");
        }
        return result;
      };
    }

    if (type == String.class) {
      return (SQLFunction<String, T>) stringConv;
    }

    if (type == Boolean.class || type == boolean.class) {
      return (SQLFunction<String, T>) boolConv;
    }

    if (type == Short.class || type == short.class) {
      return (SQLFunction<String, T>) shortConv;
    }

    if (type == Integer.class || type == int.class) {
      return (SQLFunction<String, T>) intConv;
    }

    if (type == Long.class || type == long.class) {
      return (SQLFunction<String, T>) longConv;
    }

    if (type == BigInteger.class) {
      return (SQLFunction<String, T>) bigIntConv;
    }

    if (type == Float.class || type == float.class) {
      return (SQLFunction<String, T>) floatConv;
    }

    if (type == Double.class || type == double.class) {
      return (SQLFunction<String, T>) doubleConv;
    }

    if (type == BigDecimal.class) {
      return (SQLFunction<String, T>) bigDecimalConv;
    }

    if (type == Byte.class || type == byte.class) {
      return (SQLFunction<String, T>) byteConv;
    }

    if (type == Timestamp.class) {
      return (SQLFunction<String, T>) timestampConvFn.apply(timestampUtils);
    }

    if (type == Time.class) {
      return (SQLFunction<String, T>) timeConvFn.apply(timestampUtils);
    }

    if (type == Date.class) {
      return (SQLFunction<String, T>) dateConvFn.apply(timestampUtils);
    }

    if (type == URL.class) {
      return (SQLFunction<String, T>) urlConv;
    }

    if (type == Array.class) {
      return (SQLFunction<String, T>) arrayConvFn.apply(connection);
    }

    if (type == SQLXML.class) {
      return (SQLFunction<String, T>) (value) -> (T) new PgSQLXML(connection, value);
    }

    throw new SQLException(String.format("Unsupported type conversion to [%s].", type));
  }
}
