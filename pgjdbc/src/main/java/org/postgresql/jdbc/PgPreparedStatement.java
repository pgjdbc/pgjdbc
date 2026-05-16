/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.Driver;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.CachedQuery;
import org.postgresql.core.Oid;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.ResultHandler;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.TypeInfo;
import org.postgresql.core.v3.BatchedQuery;
import org.postgresql.jdbc.codec.ArrayCodec;
import org.postgresql.jdbc.codec.DateCodec;
import org.postgresql.jdbc.codec.TimeCodec;
import org.postgresql.jdbc.codec.TimestampCodec;
import org.postgresql.jdbc.codec.TimestamptzCodec;
import org.postgresql.jdbc.codec.TimetzCodec;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.ByteStreamWriter;
import org.postgresql.util.GT;
import org.postgresql.util.HStoreConverter;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGTime;
import org.postgresql.util.PGTimestamp;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ReaderInputStream;

import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.index.qual.Positive;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.common.value.qual.IntRange;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TimeZone;
import java.util.UUID;

class PgPreparedStatement extends PgStatement implements PreparedStatement {

  protected final CachedQuery preparedQuery; // Query fragments for prepared statement.
  protected final ParameterList preparedParameters; // Parameter values for prepared statement.

  PgPreparedStatement(PgConnection connection, String sql, int rsType, int rsConcurrency,
      int rsHoldability) throws SQLException {
    this(connection, connection.borrowQuery(sql), rsType, rsConcurrency, rsHoldability);
  }

  @SuppressWarnings("method.invocation")
  PgPreparedStatement(PgConnection connection, CachedQuery query, int rsType,
      int rsConcurrency, int rsHoldability) throws SQLException {
    super(connection, rsType, rsConcurrency, rsHoldability);

    this.preparedQuery = query;
    this.preparedParameters = this.preparedQuery.query.createParameterList();
    int parameterCount = preparedParameters.getParameterCount();
    int maxSupportedParameters = maximumNumberOfParameters();
    if (parameterCount > maxSupportedParameters) {
      throw new PSQLException(
          GT.tr("PreparedStatement can have at most {0} parameters. Please consider using arrays, or splitting the query in several ones, or using COPY. Given query has {1} parameters",
              maxSupportedParameters,
              parameterCount),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    // TODO: this.wantsGeneratedKeysAlways = true;

    setPoolable(true); // As per JDBC spec: prepared and callable statements are poolable by
  }

  final int maximumNumberOfParameters() {
    return connection.getPreferQueryMode() == PreferQueryMode.SIMPLE ? Integer.MAX_VALUE : 65535;
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    throw new PSQLException(
        GT.tr("Can''t use query methods that take a query string on a PreparedStatement."),
        PSQLState.WRONG_OBJECT_TYPE);
  }

  /**
   * A Prepared SQL query is executed and its ResultSet is returned
   *
   * @return a ResultSet that contains the data produced by the * query - never null
   *
   * @throws SQLException if a database access error occurs
   */
  @Override
  public ResultSet executeQuery() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (!executeWithFlags(0)) {
        throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
      }

      return getSingleResultSet();
    }
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    throw new PSQLException(
        GT.tr("Can''t use query methods that take a query string on a PreparedStatement."),
        PSQLState.WRONG_OBJECT_TYPE);
  }

  @Override
  public int executeUpdate() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      executeWithFlags(QueryExecutor.QUERY_NO_RESULTS);
      checkNoResultUpdate();
      return getUpdateCount();
    }
  }

  @Override
  public long executeLargeUpdate() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      executeWithFlags(QueryExecutor.QUERY_NO_RESULTS);
      checkNoResultUpdate();
      return getLargeUpdateCount();
    }
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    throw new PSQLException(
        GT.tr("Can''t use query methods that take a query string on a PreparedStatement."),
        PSQLState.WRONG_OBJECT_TYPE);
  }

  @Override
  public boolean execute() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      return executeWithFlags(0);
    }
  }

  @Override
  public boolean executeWithFlags(int flags) throws SQLException {
    try {
      try (ResourceLock ignore = lock.obtain()) {
        checkClosed();

        if (connection.getPreferQueryMode() == PreferQueryMode.SIMPLE) {
          flags |= QueryExecutor.QUERY_EXECUTE_AS_SIMPLE;
        }

        execute(preparedQuery, preparedParameters, flags);

        checkClosed();
        return result != null && result.getResultSet() != null;
      }
    } finally {
      getDateTimeHelper().resetDefaultTimeZone();
    }
  }

  @Override
  protected boolean isOneShotQuery(@Nullable CachedQuery cachedQuery) {
    if (cachedQuery == null) {
      cachedQuery = preparedQuery;
    }
    return super.isOneShotQuery(cachedQuery);
  }

  @Override
  public void closeImpl() throws SQLException {
    if (preparedQuery != null) {
      ((PgConnection) connection).releaseQuery(preparedQuery);
    }
  }

  @Override
  public void setNull(int parameterIndex, int sqlType) throws SQLException {
    checkClosed();

    if (parameterIndex < 1 || parameterIndex > preparedParameters.getParameterCount()) {
      throw new PSQLException(
        GT.tr("The column index is out of range: {0}, number of columns: {1}.",
          parameterIndex, preparedParameters.getParameterCount()),
        PSQLState.INVALID_PARAMETER_VALUE);
    }

    int oid = JavaTypeRegistry.getOidForSetNull(sqlType, connection.getStringVarcharFlag());
    if (oid == -1) {
      throw new PSQLException(GT.tr("Unknown Types value."), PSQLState.INVALID_PARAMETER_TYPE);
    }
    preparedParameters.setNull(parameterIndex, oid);
  }

  @Override
  public void setBoolean(@Positive int parameterIndex, boolean x) throws SQLException {
    checkClosed();
    // The key words TRUE and FALSE are the preferred (SQL-compliant) usage.
    bindLiteral(parameterIndex, x ? "TRUE" : "FALSE", Oid.BOOL);
  }

  @Override
  public void setByte(@Positive int parameterIndex, byte x) throws SQLException {
    setShort(parameterIndex, x);
  }

  @Override
  public void setShort(@Positive int parameterIndex, short x) throws SQLException {
    checkClosed();
    if (connection.binaryTransferSend(Oid.INT2)) {
      byte[] val = new byte[2];
      ByteConverter.int2(val, 0, x);
      bindBytes(parameterIndex, val, Oid.INT2);
      return;
    }
    bindLiteral(parameterIndex, Integer.toString(x), Oid.INT2);
  }

  @Override
  public void setInt(@Positive int parameterIndex, int x) throws SQLException {
    checkClosed();
    if (connection.binaryTransferSend(Oid.INT4)) {
      byte[] val = new byte[4];
      ByteConverter.int4(val, 0, x);
      bindBytes(parameterIndex, val, Oid.INT4);
      return;
    }
    bindLiteral(parameterIndex, Integer.toString(x), Oid.INT4);
  }

  @Override
  public void setLong(@Positive int parameterIndex, long x) throws SQLException {
    checkClosed();
    if (connection.binaryTransferSend(Oid.INT8)) {
      byte[] val = new byte[8];
      ByteConverter.int8(val, 0, x);
      bindBytes(parameterIndex, val, Oid.INT8);
      return;
    }
    bindLiteral(parameterIndex, Long.toString(x), Oid.INT8);
  }

  @Override
  public void setFloat(@Positive int parameterIndex, float x) throws SQLException {
    checkClosed();
    if (connection.binaryTransferSend(Oid.FLOAT4)) {
      byte[] val = new byte[4];
      ByteConverter.float4(val, 0, x);
      bindBytes(parameterIndex, val, Oid.FLOAT4);
      return;
    }
    bindLiteral(parameterIndex, Float.toString(x), Oid.FLOAT8);
  }

  @Override
  public void setDouble(@Positive int parameterIndex, double x) throws SQLException {
    checkClosed();
    if (connection.binaryTransferSend(Oid.FLOAT8)) {
      byte[] val = new byte[8];
      ByteConverter.float8(val, 0, x);
      bindBytes(parameterIndex, val, Oid.FLOAT8);
      return;
    }
    bindLiteral(parameterIndex, Double.toString(x), Oid.FLOAT8);
  }

  @Override
  public void setBigDecimal(@Positive int parameterIndex, @Nullable BigDecimal x)
      throws SQLException {
    if (x != null && connection.binaryTransferSend(Oid.NUMERIC)) {
      final byte[] bytes = ByteConverter.numeric(x);
      bindBytes(parameterIndex, bytes, Oid.NUMERIC);
      return;
    }
    setNumber(parameterIndex, x);
  }

  @Override
  public void setString(@Positive int parameterIndex, @Nullable String x) throws SQLException {
    checkClosed();
    setString(parameterIndex, x, getStringType());
  }

  private int getStringType() {
    return connection.getStringVarcharFlag() ? Oid.VARCHAR : Oid.UNSPECIFIED;
  }

  protected void setString(@Positive int parameterIndex,
      @Nullable String x, int oid) throws SQLException {
    // if the passed string is null, then set this column to null
    checkClosed();
    if (x == null) {
      preparedParameters.setNull(parameterIndex, oid);
    } else {
      bindString(parameterIndex, x, oid);
    }
  }

  @Override
  public void setBytes(@Positive int parameterIndex, byte @Nullable[] x) throws SQLException {
    checkClosed();

    if (null == x) {
      setNull(parameterIndex, Types.VARBINARY);
      return;
    }

    // Version 7.2 supports the bytea datatype for byte arrays
    byte[] copy = new byte[x.length];
    System.arraycopy(x, 0, copy, 0, x.length);
    preparedParameters.setBytea(parameterIndex, copy, 0, x.length);
  }

  private void setByteStreamWriter(@Positive int parameterIndex,
      ByteStreamWriter x) throws SQLException {
    preparedParameters.setBytea(parameterIndex, x);
  }

  @Override
  public void setDate(@Positive int parameterIndex,
      @Nullable Date x) throws SQLException {
    setDate(parameterIndex, x, null);
  }

  @Override
  public void setTime(@Positive int parameterIndex, @Nullable Time x) throws SQLException {
    setTime(parameterIndex, x, null);
  }

  @Override
  public void setTimestamp(@Positive int parameterIndex, @Nullable Timestamp x) throws SQLException {
    setTimestamp(parameterIndex, x, null);
  }

  private void setCharacterStreamPost71(@Positive int parameterIndex,
      @Nullable InputStream x, int length,
      String encoding) throws SQLException {

    if (x == null) {
      setNull(parameterIndex, Types.VARCHAR);
      return;
    }
    if (length < 0) {
      throw new PSQLException(GT.tr("Invalid stream length {0}.", length),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    // Version 7.2 supports AsciiStream for all PG text types (char, varchar, text)
    // As the spec/javadoc for this method indicate this is to be used for
    // large String values (i.e. LONGVARCHAR) PG doesn't have a separate
    // long varchar datatype, but with toast all text datatypes are capable of
    // handling very large values. Thus the implementation ends up calling
    // setString() since there is no current way to stream the value to the server
    try {
      InputStreamReader inStream = new InputStreamReader(x, Charset.forName(encoding));
      char[] chars = new char[length];
      int charsRead = 0;
      while (true) {
        int n = inStream.read(chars, charsRead, length - charsRead);
        if (n == -1) {
          break;
        }

        charsRead += n;

        if (charsRead == length) {
          break;
        }
      }

      setString(parameterIndex, new String(chars, 0, charsRead), Oid.VARCHAR);
    } catch (UnsupportedCharsetException uce) {
      throw new PSQLException(GT.tr("The JVM claims not to support the {0} encoding.", encoding),
          PSQLState.UNEXPECTED_ERROR, uce);
    } catch (IOException ioe) {
      throw new PSQLException(GT.tr("Provided InputStream failed."), PSQLState.UNEXPECTED_ERROR,
          ioe);
    }
  }

  @Override
  public void setAsciiStream(@Positive int parameterIndex, @Nullable InputStream x,
      @NonNegative int length) throws SQLException {
    checkClosed();
    setCharacterStreamPost71(parameterIndex, x, length, "ASCII");
  }

  @Override
  @SuppressWarnings("deprecation")
  public void setUnicodeStream(@Positive int parameterIndex, @Nullable InputStream x,
      @NonNegative int length) throws SQLException {
    checkClosed();

    setCharacterStreamPost71(parameterIndex, x, length, "UTF-8");
  }

  @Override
  public void setBinaryStream(@Positive int parameterIndex, @Nullable InputStream x,
      @NonNegative int length) throws SQLException {
    // Version 7.2 supports BinaryStream for the PG bytea type
    // As the spec/javadoc for this method indicate this is to be used for
    // large binary values (i.e. LONGVARBINARY) PG doesn't have a separate
    // long binary datatype, but with toast the bytea datatype is capable of
    // handling very large values.
    setBinaryStream(parameterIndex, x, (long) length);
  }

  @Override
  public void clearParameters() throws SQLException {
    preparedParameters.clear();
  }

  // Helper method for setting parameters to PGobject subclasses.
  private void setPGobject(@Positive int parameterIndex, PGobject x) throws SQLException {
    String typename = x.getType();
    PgType pgType = connection.getTypeInfo().getPgTypeByPgName(typename);
    int oid = pgType.getOid();

    if ((x instanceof PGBinaryObject) && connection.binaryTransferSend(oid)) {
      PGBinaryObject binObj = (PGBinaryObject) x;
      int length = binObj.lengthInBytes();
      if (length == 0) {
        preparedParameters.setNull(parameterIndex, oid);
        return;
      }
      byte[] data = new byte[length];
      binObj.toBytes(data, 0);
      bindBytes(parameterIndex, data, oid);
    } else {
      setString(parameterIndex, x.getValue(), oid);
    }
  }

  private void setMap(@Positive int parameterIndex, Map<?, ?> x) throws SQLException {
    int oid = connection.getTypeInfo().getPgTypeByPgName("hstore").getOid();
    if (oid == Oid.UNSPECIFIED) {
      throw new PSQLException(GT.tr("No hstore extension installed."),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    if (connection.binaryTransferSend(oid)) {
      byte[] data = HStoreConverter.toBytes(x, connection.getEncoding());
      bindBytes(parameterIndex, data, oid);
    } else {
      setString(parameterIndex, HStoreConverter.toString(x), oid);
    }
  }

  private void setNumber(@Positive int parameterIndex, @Nullable Number x) throws SQLException {
    checkClosed();
    if (x == null) {
      setNull(parameterIndex, Types.DECIMAL);
    } else {
      bindLiteral(parameterIndex, x.toString(), Oid.NUMERIC);
    }
  }

  @Override
  public void setObject(@Positive int parameterIndex, @Nullable Object in,
      int targetSqlType, int scale)
      throws SQLException {
    checkClosed();

    if (in == null) {
      setNull(parameterIndex, targetSqlType);
      return;
    }

    if (targetSqlType == Types.OTHER && in instanceof UUID
        && connection.haveMinimumServerVersion(ServerVersion.v8_3)) {
      setUuid(parameterIndex, (UUID) in);
      return;
    }

    switch (targetSqlType) {
      case Types.SQLXML:
        if (in instanceof SQLXML) {
          setSQLXML(parameterIndex, (SQLXML) in);
        } else {
          setSQLXML(parameterIndex, new PgSQLXML(connection, in.toString()));
        }
        break;
      case Types.INTEGER:
        setInt(parameterIndex, castToInt(in));
        break;
      case Types.TINYINT:
      case Types.SMALLINT:
        setShort(parameterIndex, castToShort(in));
        break;
      case Types.BIGINT:
        setLong(parameterIndex, castToLong(in));
        break;
      case Types.REAL:
        setFloat(parameterIndex, castToFloat(in));
        break;
      case Types.DOUBLE:
      case Types.FLOAT:
        setDouble(parameterIndex, castToDouble(in));
        break;
      case Types.DECIMAL:
      case Types.NUMERIC:
        setBigDecimal(parameterIndex, castToBigDecimal(in, scale));
        break;
      case Types.CHAR:
        setString(parameterIndex, castToString(in), Oid.BPCHAR);
        break;
      case Types.VARCHAR:
        setString(parameterIndex, castToString(in), getStringType());
        break;
      case Types.LONGVARCHAR:
        if (in instanceof InputStream) {
          preparedParameters.setText(parameterIndex, (InputStream) in);
        } else {
          setString(parameterIndex, castToString(in), getStringType());
        }
        break;
      case Types.DATE:
        if (in instanceof Date) {
          setDate(parameterIndex, (Date) in);
        } else if (in instanceof java.util.Date) {
          @SuppressWarnings("JavaUtilDate")
          Date res = new Date(((java.util.Date) in).getTime());
          setDate(parameterIndex, res);
        } else {
          // Handles LocalDate and other types via DateCodec
          encodeViaCodec(parameterIndex, in, DateCodec.INSTANCE, Oid.DATE);
        }
        break;
      case Types.TIME:
        if (in instanceof Time) {
          setTime(parameterIndex, (Time) in);
        } else if (in instanceof java.util.Date) {
          @SuppressWarnings("JavaUtilDate")
          Time res = new Time(((java.util.Date) in).getTime());
          setTime(parameterIndex, res);
        } else if (in instanceof java.time.OffsetTime) {
          // Legacy contract: an OffsetTime passed with Types.TIME is bound as
          // timetz so the offset survives the round-trip — TIME (no tz) would
          // silently drop it.
          encodeViaCodec(parameterIndex, in, TimetzCodec.INSTANCE, Oid.TIMETZ);
        } else {
          // Handles LocalTime and other types via TimeCodec
          encodeViaCodec(parameterIndex, in, TimeCodec.INSTANCE, Oid.TIME);
        }
        break;
      case Types.TIME_WITH_TIMEZONE:
        if (in instanceof Time) {
          setTime(parameterIndex, (Time) in);
        } else {
          // Handles OffsetTime, LocalTime and other types via TimetzCodec
          encodeViaCodec(parameterIndex, in, TimetzCodec.INSTANCE, Oid.TIMETZ);
        }
        break;
      case Types.TIMESTAMP:
        if (in instanceof PGTimestamp) {
          setObject(parameterIndex, in);
        } else if (in instanceof Timestamp) {
          setTimestamp(parameterIndex, (Timestamp) in);
        } else if (in instanceof java.util.Date) {
          @SuppressWarnings("JavaUtilDate")
          Timestamp res = new Timestamp(((java.util.Date) in).getTime());
          setTimestamp(parameterIndex, res);
        } else {
          // Handles LocalDateTime, OffsetDateTime, ZonedDateTime, Instant via TimestampCodec
          encodeViaCodec(parameterIndex, in, TimestampCodec.INSTANCE, Oid.TIMESTAMP);
        }
        break;
      case Types.TIMESTAMP_WITH_TIMEZONE:
        if (in instanceof PGTimestamp) {
          setObject(parameterIndex, in);
        } else if (in instanceof Timestamp) {
          setTimestamp(parameterIndex, (Timestamp) in);
        } else {
          // Handles OffsetDateTime, ZonedDateTime, Instant, LocalDateTime via TimestamptzCodec
          encodeViaCodec(parameterIndex, in, TimestamptzCodec.INSTANCE, Oid.TIMESTAMPTZ);
        }
        break;
      case Types.BOOLEAN:
      case Types.BIT:
        setBoolean(parameterIndex, BooleanTypeUtil.castToBoolean(in));
        break;
      case Types.BINARY:
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
        setObject(parameterIndex, in);
        break;
      case Types.BLOB:
        if (in instanceof Blob) {
          setBlob(parameterIndex, (Blob) in);
        } else if (in instanceof InputStream) {
          long oid = createBlob((InputStream) in, Long.MAX_VALUE);
          setLong(parameterIndex, oid);
        } else {
          throw new PSQLException(
              GT.tr("Cannot cast an instance of {0} to type {1}",
                  in.getClass().getName(), "Types.BLOB"),
              PSQLState.INVALID_PARAMETER_TYPE);
        }
        break;
      case Types.CLOB:
        if (in instanceof Clob) {
          setClob(parameterIndex, (Clob) in);
        } else {
          throw new PSQLException(
              GT.tr("Cannot cast an instance of {0} to type {1}",
                  in.getClass().getName(), "Types.CLOB"),
              PSQLState.INVALID_PARAMETER_TYPE);
        }
        break;
      case Types.ARRAY:
        if (in instanceof Array) {
          setArray(parameterIndex, (Array) in);
        } else {
          try {
            setObjectArray(parameterIndex, in);
          } catch (Exception e) {
            throw new PSQLException(
                GT.tr("Cannot cast an instance of {0} to type {1}", in.getClass().getName(), "Types.ARRAY"),
                PSQLState.INVALID_PARAMETER_TYPE, e);
          }
        }
        break;
      case Types.DISTINCT:
        bindString(parameterIndex, in.toString(), Oid.UNSPECIFIED);
        break;
      case Types.STRUCT:
        setObject(parameterIndex, in);
        break;
      case Types.OTHER:
        if (in instanceof PGobject) {
          setPGobject(parameterIndex, (PGobject) in);
        } else if (in instanceof Map) {
          setMap(parameterIndex, (Map<?, ?>) in);
        } else {
          bindString(parameterIndex, in.toString(), Oid.UNSPECIFIED);
        }
        break;
      default:
        throw new PSQLException(GT.tr("Unsupported Types value: {0}", targetSqlType),
            PSQLState.INVALID_PARAMETER_TYPE);
    }
  }

  private static Class<?> getArrayType(Class<?> type) {
    Class<?> subType = type.getComponentType();
    while (subType != null) {
      type = subType;
      subType = type.getComponentType();
    }
    return type;
  }

  private static Class<?> getArrayElementType(Class<?> arrayClass) {
    Class<?> arrayType = getArrayType(arrayClass);
    if (!arrayType.isPrimitive()) {
      return arrayType;
    }
    if (arrayType == int.class) {
      return Integer.class;
    }
    if (arrayType == long.class) {
      return Long.class;
    }
    if (arrayType == short.class) {
      return Short.class;
    }
    if (arrayType == double.class) {
      return Double.class;
    }
    if (arrayType == float.class) {
      return Float.class;
    }
    if (arrayType == boolean.class) {
      return Boolean.class;
    }
    if (arrayType == byte.class) {
      return byte[].class;
    }
    return arrayType;
  }

  @SuppressWarnings("deprecation")
  private void setObjectArray(int parameterIndex, Object in) throws SQLException {
    ArrayCodec.validateJavaArray(in);
    TypeInfo typeInfo = connection.getTypeInfo();
    Class<?> arrayType = getArrayElementType(in.getClass());
    int oid = typeInfo.getJavaArrayType(arrayType);
    if (oid == Oid.UNSPECIFIED) {
      throw new SQLFeatureNotSupportedException();
    }

    PgType arrayTypeInfo = typeInfo.getPgTypeByOid(oid);
    CodecContext ctx = connection.getCodecContext();
    CodecRegistry codecs = ctx.getCodecs();
    if (connection.getPreferQueryMode() != PreferQueryMode.SIMPLE) {
      BinaryCodec codec = codecs.getBinaryCodec(oid, arrayTypeInfo);
      if (codec != null) {
        bindBytes(parameterIndex, codec.encodeBinary(in, arrayTypeInfo, ctx), oid);
        return;
      }
    }

    TextCodec codec = codecs.getTextCodec(oid, arrayTypeInfo);
    if (codec == null) {
      throw new PSQLException(
          GT.tr("No text codec registered for type {0}", arrayTypeInfo.getTypeName()),
          PSQLState.SYSTEM_ERROR);
    }
    bindString(parameterIndex, codec.encodeText(in, arrayTypeInfo, ctx), oid);
  }

  private static int castToInt(final Object in) throws SQLException {
    return TypeCoercion.toInt(in);
  }

  private static short castToShort(final Object in) throws SQLException {
    return TypeCoercion.toShort(in);
  }

  private static long castToLong(final Object in) throws SQLException {
    return TypeCoercion.toLong(in);
  }

  private static float castToFloat(final Object in) throws SQLException {
    return TypeCoercion.toFloat(in);
  }

  private static double castToDouble(final Object in) throws SQLException {
    return TypeCoercion.toDouble(in);
  }

  private static BigDecimal castToBigDecimal(final Object in, final int scale) throws SQLException {
    return TypeCoercion.toBigDecimal(in, scale);
  }

  private static String castToString(final Object in) throws SQLException {
    return TypeCoercion.toString(in);
  }

  @Override
  public void setObject(@Positive int parameterIndex, @Nullable Object x,
      int targetSqlType) throws SQLException {
    setObject(parameterIndex, x, targetSqlType, -1);
  }

  /*
   * This stores an Object into a parameter.
   */
  @Override
  public void setObject(@Positive int parameterIndex, @Nullable Object x) throws SQLException {
    checkClosed();
    if (x == null) {
      setNull(parameterIndex, Types.OTHER);
    } else if (x instanceof UUID && connection.haveMinimumServerVersion(ServerVersion.v8_3)) {
      setUuid(parameterIndex, (UUID) x);
    } else if (x instanceof SQLXML) {
      setSQLXML(parameterIndex, (SQLXML) x);
    } else if (x instanceof String) {
      setString(parameterIndex, (String) x);
    } else if (x instanceof BigDecimal) {
      setBigDecimal(parameterIndex, (BigDecimal) x);
    } else if (x instanceof Short) {
      setShort(parameterIndex, (Short) x);
    } else if (x instanceof Integer) {
      setInt(parameterIndex, (Integer) x);
    } else if (x instanceof Long) {
      setLong(parameterIndex, (Long) x);
    } else if (x instanceof Float) {
      setFloat(parameterIndex, (Float) x);
    } else if (x instanceof Double) {
      setDouble(parameterIndex, (Double) x);
    } else if (x instanceof byte[]) {
      setBytes(parameterIndex, (byte[]) x);
    } else if (x instanceof ByteStreamWriter) {
      setByteStreamWriter(parameterIndex, (ByteStreamWriter) x);
    } else if (x instanceof Date) {
      setDate(parameterIndex, (Date) x);
    } else if (x instanceof Time) {
      setTime(parameterIndex, (Time) x);
    } else if (x instanceof Timestamp) {
      setTimestamp(parameterIndex, (Timestamp) x);
    } else if (x instanceof Boolean) {
      setBoolean(parameterIndex, (Boolean) x);
    } else if (x instanceof Byte) {
      setByte(parameterIndex, (Byte) x);
    } else if (x instanceof Blob) {
      setBlob(parameterIndex, (Blob) x);
    } else if (x instanceof Clob) {
      setClob(parameterIndex, (Clob) x);
    } else if (x instanceof Array) {
      setArray(parameterIndex, (Array) x);
    } else if (x instanceof java.sql.Struct) {
      // PgStruct extends PGobject so we have to check Struct before PGobject;
      // setPGobject would otherwise bind the (null) value field as NULL.
      setStruct(parameterIndex, (java.sql.Struct) x);
    } else if (x instanceof java.sql.SQLData) {
      // Same reasoning: a SQLData implementation may also subclass PGobject
      // and we want the composite-encoding path.
      setSQLData(parameterIndex, (java.sql.SQLData) x);
    } else if (x instanceof PGobject) {
      setPGobject(parameterIndex, (PGobject) x);
    } else if (x instanceof Character) {
      setString(parameterIndex, ((Character) x).toString());
    } else if (x instanceof LocalDate) {
      setDate(parameterIndex, (LocalDate) x);
    } else if (x instanceof LocalTime) {
      setTime(parameterIndex, (LocalTime) x);
    } else if (x instanceof OffsetTime) {
      setTime(parameterIndex, (OffsetTime) x);
    } else if (x instanceof LocalDateTime) {
      setTimestamp(parameterIndex, (LocalDateTime) x);
    } else if (x instanceof OffsetDateTime) {
      setTimestamp(parameterIndex, (OffsetDateTime) x);
    } else if (x instanceof Instant) {
      encodeViaCodec(parameterIndex, x, TimestamptzCodec.INSTANCE, Oid.TIMESTAMPTZ);
    } else if (x instanceof ZonedDateTime) {
      encodeViaCodec(parameterIndex, x, TimestamptzCodec.INSTANCE, Oid.TIMESTAMPTZ);
    } else if (x instanceof Map) {
      setMap(parameterIndex, (Map<?, ?>) x);
    } else if (x instanceof Number) {
      setNumber(parameterIndex, (Number) x);
    } else if (x.getClass().isArray()) {
      try {
        setObjectArray(parameterIndex, x);
      } catch (Exception e) {
        throw new PSQLException(
            GT.tr("Cannot cast an instance of {0} to type {1}", x.getClass().getName(), "Types.ARRAY"),
            PSQLState.INVALID_PARAMETER_TYPE, e);
      }
    } else if (x instanceof java.sql.SQLData) {
      // Handle SQLData — encode via the registered composite codec.
      setSQLData(parameterIndex, (java.sql.SQLData) x);
    } else if (x instanceof java.sql.Struct) {
      // Handle Struct - encode as composite type
      setStruct(parameterIndex, (java.sql.Struct) x);
    } else {
      // Can't infer a type.
      throw new PSQLException(GT.tr(
          "Can''t infer the SQL type to use for an instance of {0}. Use setObject() with an explicit Types value to specify the type to use.",
          x.getClass().getName()), PSQLState.INVALID_PARAMETER_TYPE);
    }
  }

  /**
   * Returns the SQL statement with the current template values substituted.
   *
   * @return SQL statement with the current template values substituted
   */
  @Override
  public String toString() {
    if (preparedQuery == null) {
      return super.toString();
    }

    List<Query> batchStatements = this.batchStatements;
    if (batchStatements == null || batchStatements.isEmpty()) {
      return preparedQuery.query.toString(preparedParameters);
    }
    List<@Nullable ParameterList> batchParameters = this.batchParameters;
    StringJoiner sj = new StringJoiner(";\n");
    if (batchStatements.size() == 1 && batchParameters != null) {
      // For rewritebatchinserts=true case, we have a single batch statement and multiple parameter rows
      Query query = batchStatements.get(0);
      for (ParameterList batchParameter : batchParameters) {
        sj.add(query.toString(batchParameter));
      }
    } else {
      for (int i = 0; i < batchStatements.size(); i++) {
        Query statement = batchStatements.get(i);
        sj.add(statement.toString(batchParameters == null ? null : batchParameters.get(i)));
      }
    }
    return sj.toString();
  }

  /**
   * Note if s is a String it should be escaped by the caller to avoid SQL injection attacks. It is
   * not done here for efficiency reasons as most calls to this method do not require escaping as
   * the source of the string is known safe (i.e. {@code Integer.toString()})
   *
   * @param paramIndex parameter index
   * @param s value (the value should already be escaped)
   * @param oid type oid
   * @throws SQLException if something goes wrong
   */
  protected void bindLiteral(@Positive int paramIndex,
      String s, int oid) throws SQLException {
    preparedParameters.setLiteralParameter(paramIndex, s, oid);
  }

  protected void bindBytes(@Positive int paramIndex,
      byte[] b, int oid) throws SQLException {
    preparedParameters.setBinaryParameter(paramIndex, b, oid);
  }

  /**
   * This version is for values that should turn into strings e.g. setString directly calls
   * bindString with no escaping; the per-protocol ParameterList does escaping as needed.
   *
   * @param paramIndex parameter index
   * @param s value
   * @param oid type oid
   * @throws SQLException if something goes wrong
   */
  private void bindString(@Positive int paramIndex, String s, int oid) throws SQLException {
    preparedParameters.setStringParameter(paramIndex, s, oid);
  }

  /**
   * Encodes a value using the given codec's text encoding and binds it as a string parameter.
   *
   * @param parameterIndex the parameter index (1-based)
   * @param value the value to encode
   * @param codec the text codec to use for encoding
   * @param oid the PostgreSQL type OID to bind with
   * @throws SQLException if encoding fails
   */
  private void encodeViaCodec(@Positive int parameterIndex, Object value, TextCodec codec, int oid)
      throws SQLException {
    PgType pgType = connection.getTypeInfo().getPgTypeByOid(oid);
    CodecContext ctx = connection.getCodecContext();
    String text = codec.encodeText(value, pgType, ctx);
    bindString(parameterIndex, text, oid);
  }

  @Override
  public boolean isUseServerPrepare() {
    return preparedQuery != null && mPrepareThreshold != 0
        && preparedQuery.getExecuteCount() + 1 >= mPrepareThreshold;
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    checkClosed();

    throw new PSQLException(
        GT.tr("Can''t use query methods that take a query string on a PreparedStatement."),
        PSQLState.WRONG_OBJECT_TYPE);
  }

  @Override
  public void addBatch() throws SQLException {
    checkClosed();
    ArrayList<Query> batchStatements = this.batchStatements;
    if (batchStatements == null) {
      this.batchStatements = batchStatements = new ArrayList<>();
    }
    ArrayList<@Nullable ParameterList> batchParameters = this.batchParameters;
    if (batchParameters == null) {
      this.batchParameters = batchParameters = new ArrayList<@Nullable ParameterList>();
    }
    // we need to create copies of our parameters, otherwise the values can be changed
    batchParameters.add(preparedParameters.copy());
    Query query = preparedQuery.query;
    if (!(query instanceof BatchedQuery) || batchStatements.isEmpty()) {
      batchStatements.add(query);
    }
  }

  @Override
  public @Nullable ResultSetMetaData getMetaData() throws SQLException {
    checkClosed();
    ResultSet rs = getResultSet();

    if (rs == null || ((PgResultSet) rs).isResultSetClosed()) {
      // OK, we haven't executed it yet, or it was closed
      // we've got to go to the backend
      // for more info. We send the full query, but just don't
      // execute it.

      int flags = QueryExecutor.QUERY_DESCRIBE_ONLY
          | QueryExecutor.QUERY_SUPPRESS_BEGIN;
      StatementResultHandler handler = new StatementResultHandler();
      connection.getQueryExecutor().execute(preparedQuery.query, preparedParameters, handler, 0, 0,
          flags);
      ResultWrapper wrapper = handler.getResults();
      if (wrapper != null) {
        rs = wrapper.getResultSet();
      }
    }

    if (rs != null) {
      return rs.getMetaData();
    }

    return null;
  }

  @Override
  public void setArray(int i, @Nullable Array x) throws SQLException {
    checkClosed();

    if (null == x) {
      setNull(i, Types.ARRAY);
      return;
    }

    int oid;
    if (x instanceof PgArray) {
      oid = ((PgArray) x).getOid();
    } else {
      String typename = x.getBaseTypeName();
      PgType arrayType = connection.getTypeInfo().getPgTypeByPgName(typename);
      oid = arrayType.getOid();
    }

    if (x instanceof PgArray) {
      PgArray arr = (PgArray) x;
      byte[] bytes = arr.toBytes();
      if (bytes != null) {
        bindBytes(i, bytes, oid);
        return;
      }
    }

    // This only works for Array implementations that return a valid array
    // literal from Array.toString(), such as the implementation we return
    // from ResultSet.getArray(). Eventually we need a proper implementation
    // here that works for any Array implementation.
    setString(i, x.toString(), oid);
  }

  protected long createBlob(InputStream inputStream,
      @NonNegative long length) throws SQLException {
    LargeObjectManager lom = connection.getLargeObjectAPI();
    long oid = lom.createLO();
    try (LargeObject lob = lom.open(oid);
         OutputStream outputStream = lob.getOutputStream()) {
      // The actual buffer size does not matter much, see benchmarks
      // https://github.com/pgjdbc/pgjdbc/pull/3044#issuecomment-1838057929
      // BlobOutputStream would gradually increase the buffer, so it will level the number of
      // database calls.
      // At the same time, inputStream.read might produce less rows than requested, so we can not
      // use a plain lob.write(buf, 0, numRead) as it might not align with 2K boundaries.
      byte[] buf = new byte[(int) Math.min(length, 8192)];
      int numRead;
      while (length > 0 && (
          numRead = inputStream.read(buf, 0, (int) Math.min(buf.length, length))) >= 0) {
        length -= numRead;
        outputStream.write(buf, 0, numRead);
      }
    } catch (IOException se) {
      throw new PSQLException(GT.tr("Unexpected error writing large object to database."),
          PSQLState.UNEXPECTED_ERROR, se);
    }
    return oid;
  }

  @Override
  public void setBlob(@Positive int i, @Nullable Blob x) throws SQLException {
    checkClosed();

    if (x == null) {
      setNull(i, Types.BLOB);
      return;
    }

    try (InputStream inStream = x.getBinaryStream(); ) {
      long maxLength = x.length();
      if (maxLength < 0) {
        // Hibernate used to create blob instances that report -1 length, so we ignore the length
        // and assume we need to read all the data from the stream.
        // See https://github.com/pgjdbc/pgjdbc/issues/3134
        maxLength = Long.MAX_VALUE;
      }
      long oid = createBlob(inStream, maxLength);
      setLong(i, oid);
    } catch (IOException e) {
      throw new PSQLException(GT.tr("Unexpected error when closing Blob binary stream"),
          PSQLState.UNEXPECTED_ERROR, e);
    }
  }

  private static String readerToString(Reader value, int maxLength) throws SQLException {
    try {
      int bufferSize = Math.min(maxLength, 1024);
      StringBuilder v = new StringBuilder(bufferSize);
      char[] buf = new char[bufferSize];
      int nRead = 0;
      while (nRead > -1 && v.length() < maxLength) {
        nRead = value.read(buf, 0, Math.min(bufferSize, maxLength - v.length()));
        if (nRead > 0) {
          v.append(buf, 0, nRead);
        }
      }
      return v.toString();
    } catch (IOException ioe) {
      throw new PSQLException(GT.tr("Provided Reader failed."), PSQLState.UNEXPECTED_ERROR, ioe);
    }
  }

  @Override
  public void setCharacterStream(@Positive int i, @Nullable Reader x,
      @NonNegative int length) throws SQLException {
    checkClosed();

    if (x == null) {
      setNull(i, Types.VARCHAR);
      return;
    }

    if (length < 0) {
      throw new PSQLException(GT.tr("Invalid stream length {0}.", length),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    // Version 7.2 supports CharacterStream for the PG text types
    // As the spec/javadoc for this method indicate this is to be used for
    // large text values (i.e. LONGVARCHAR) PG doesn't have a separate
    // long varchar datatype, but with toast all the text datatypes are capable of
    // handling very large values. Thus the implementation ends up calling
    // setString() since there is no current way to stream the value to the server
    setString(i, readerToString(x, length));
  }

  @Override
  public void setClob(@Positive int i, @Nullable Clob x) throws SQLException {
    checkClosed();

    if (x == null) {
      setNull(i, Types.CLOB);
      return;
    }

    Reader inStream = x.getCharacterStream();
    int length = (int) x.length();
    if (length < 0) {
      // See #setBlob(int, Blob)
      length = Integer.MAX_VALUE;
    }
    LargeObjectManager lom = connection.getLargeObjectAPI();
    long oid = lom.createLO();
    LargeObject lob = lom.open(oid);
    Charset connectionCharset = Charset.forName(connection.getEncoding().name());
    OutputStream los = lob.getOutputStream();
    Writer lw = new OutputStreamWriter(los, connectionCharset);
    try {
      // could be buffered, but then the OutputStream returned by LargeObject
      // is buffered internally anyhow, so there would be no performance
      // boost gained, if anything it would be worse!
      int c = inStream.read();
      int p = 0;
      while (c > -1 && p < length) {
        lw.write(c);
        c = inStream.read();
        p++;
      }
      lw.close();
    } catch (IOException se) {
      throw new PSQLException(GT.tr("Unexpected error writing large object to database."),
          PSQLState.UNEXPECTED_ERROR, se);
    }
    // lob is closed by the stream so don't call lob.close()
    setLong(i, oid);
  }

  @Override
  public void setNull(@Positive int parameterIndex, int t,
      @Nullable String typeName) throws SQLException {
    if (typeName == null) {
      setNull(parameterIndex, t);
      return;
    }

    checkClosed();

    TypeInfo typeInfo = connection.getTypeInfo();
    int oid = typeInfo.getPgTypeByPgName(typeName).getOid();

    preparedParameters.setNull(parameterIndex, oid);
  }

  @Override
  public void setRef(@Positive int i, @Nullable Ref x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setRef(int,Ref)");
  }

  @Override
  public void setDate(@Positive int i, @Nullable Date d,
      @Nullable Calendar cal) throws SQLException {
    checkClosed();

    if (d == null) {
      setNull(i, Types.DATE);
      return;
    }

    if (connection.binaryTransferSend(Oid.DATE)) {
      byte[] val = new byte[4];
      TimeZone tz = cal != null ? cal.getTimeZone() : null;
      getTimestampUtils().toBinDate(tz, val, d);
      preparedParameters.setBinaryParameter(i, val, Oid.DATE);
      return;
    }

    // We must use UNSPECIFIED here, or inserting a Date-with-timezone into a
    // timestamptz field does an unexpected rotation by the server's TimeZone:
    //
    // We want to interpret 2005/01/01 with calendar +0100 as
    // "local midnight in +0100", but if we go via date it interprets it
    // as local midnight in the server's timezone:

    // template1=# select '2005-01-01+0100'::timestamptz;
    // timestamptz
    // ------------------------
    // 2005-01-01 02:00:00+03
    // (1 row)

    // template1=# select '2005-01-01+0100'::date::timestamptz;
    // timestamptz
    // ------------------------
    // 2005-01-01 00:00:00+03
    // (1 row)

    if (cal == null) {
      cal = getDefaultCalendar();
    }
    bindString(i, getTimestampUtils().toString(cal, d), Oid.UNSPECIFIED);
  }

  @Override
  public void setTime(@Positive int i, @Nullable Time t,
      @Nullable Calendar cal) throws SQLException {
    checkClosed();

    if (t == null) {
      setNull(i, Types.TIME);
      return;
    }

    int oid = Oid.UNSPECIFIED;

    // If a PGTime is used, we can define the OID explicitly.
    if (t instanceof PGTime) {
      PGTime pgTime = (PGTime) t;
      if (pgTime.getCalendar() == null) {
        oid = Oid.TIME;
      } else {
        oid = Oid.TIMETZ;
        cal = pgTime.getCalendar();
      }
    }

    if (cal == null) {
      cal = getDefaultCalendar();
    }
    bindString(i, getTimestampUtils().toString(cal, t), oid);
  }

  @Override
  public void setTimestamp(@Positive int i, @Nullable Timestamp t,
      @Nullable Calendar cal) throws SQLException {
    checkClosed();

    if (t == null) {
      setNull(i, Types.TIMESTAMP);
      return;
    }

    int oid = Oid.UNSPECIFIED;

    // Use UNSPECIFIED as a compromise to get both TIMESTAMP and TIMESTAMPTZ working.
    // This is because you get this in a +1300 timezone:
    //
    // template1=# select '2005-01-01 15:00:00 +1000'::timestamptz;
    // timestamptz
    // ------------------------
    // 2005-01-01 18:00:00+13
    // (1 row)

    // template1=# select '2005-01-01 15:00:00 +1000'::timestamp;
    // timestamp
    // ---------------------
    // 2005-01-01 15:00:00
    // (1 row)

    // template1=# select '2005-01-01 15:00:00 +1000'::timestamptz::timestamp;
    // timestamp
    // ---------------------
    // 2005-01-01 18:00:00
    // (1 row)

    // So we want to avoid doing a timestamptz -> timestamp conversion, as that
    // will first convert the timestamptz to an equivalent time in the server's
    // timezone (+1300, above), then turn it into a timestamp with the "wrong"
    // time compared to the string we originally provided. But going straight
    // to timestamp is OK as the input parser for timestamp just throws away
    // the timezone part entirely. Since we don't know ahead of time what type
    // we're actually dealing with, UNSPECIFIED seems the lesser evil, even if it
    // does give more scope for type-mismatch errors being silently hidden.

    // If a PGTimestamp is used, we can define the OID explicitly.
    if (t instanceof PGTimestamp) {
      PGTimestamp pgTimestamp = (PGTimestamp) t;
      if (pgTimestamp.getCalendar() == null) {
        oid = Oid.TIMESTAMP;
      } else {
        oid = Oid.TIMESTAMPTZ;
        cal = pgTimestamp.getCalendar();
      }
    }
    if (cal == null) {
      cal = getDefaultCalendar();
    }
    bindString(i, getTimestampUtils().toString(cal, t), oid);
  }

  private void setDate(@Positive int i, LocalDate localDate) throws SQLException {
    int oid = Oid.DATE;
    bindString(i, getTimestampUtils().toString(localDate), oid);
  }

  private void setTime(@Positive int i, LocalTime localTime) throws SQLException {
    int oid = Oid.TIME;
    bindString(i, getTimestampUtils().toString(localTime), oid);
  }

  private void setTime(@Positive int i, OffsetTime offsetTime) throws SQLException {
    int oid = Oid.TIMETZ;
    bindString(i, getTimestampUtils().toString(offsetTime), oid);
  }

  private void setTimestamp(@Positive int i, LocalDateTime localDateTime)
      throws SQLException {
    int oid = Oid.TIMESTAMP;
    bindString(i, getTimestampUtils().toString(localDateTime), oid);
  }

  private void setTimestamp(@Positive int i, OffsetDateTime offsetDateTime)
      throws SQLException {
    int oid = Oid.TIMESTAMPTZ;
    bindString(i, getTimestampUtils().toString(offsetDateTime), oid);
  }

  public ParameterMetaData createParameterMetaData(BaseConnection conn, int[] oids)
      throws SQLException {
    return new PgParameterMetaData(conn, oids);
  }

  @Override
  public void setObject(@Positive int parameterIndex, @Nullable Object x,
      SQLType targetSqlType,
      int scaleOrLength) throws SQLException {
    checkClosed();

    int sqlTypeCode = JavaTypeRegistry.getSqlTypeCode(targetSqlType);

    if (x == null) {
      setNull(parameterIndex, sqlTypeCode);
      return;
    }

    // Handle SQLData - encode using CompositeCodec
    if (x instanceof java.sql.SQLData) {
      setSQLData(parameterIndex, (java.sql.SQLData) x);
      return;
    }

    // Handle Struct - encode as composite type
    if (x instanceof java.sql.Struct) {
      setStruct(parameterIndex, (java.sql.Struct) x);
      return;
    }

    // Delegate to int-based setObject
    setObject(parameterIndex, x, sqlTypeCode, scaleOrLength);
  }

  @Override
  public void setObject(@Positive int parameterIndex, @Nullable Object x,
      SQLType targetSqlType)
      throws SQLException {
    // Use default scale of 0
    setObject(parameterIndex, x, targetSqlType, 0);
  }

  /**
   * Sets an SQLData parameter value.
   *
   * @param parameterIndex the parameter index (1-based)
   * @param sqlData the SQLData value
   * @throws SQLException if an error occurs
   */
  private void setSQLData(@Positive int parameterIndex, java.sql.SQLData sqlData)
      throws SQLException {
    // getPgTypeByPgName throws SQLException if type is not found
    PgType pgType = connection.getTypeInfo().getPgTypeByPgName(sqlData.getSQLTypeName());
    bindViaCodec(parameterIndex, sqlData, pgType);
  }

  /**
   * Sets a Struct parameter value.
   *
   * @param parameterIndex the parameter index (1-based)
   * @param struct the Struct value
   * @throws SQLException if an error occurs
   */
  private void setStruct(@Positive int parameterIndex, java.sql.Struct struct) throws SQLException {
    // getPgTypeByPgName throws SQLException if type is not found
    PgType pgType = connection.getTypeInfo().getPgTypeByPgName(struct.getSQLTypeName());
    bindViaCodec(parameterIndex, struct, pgType);
  }

  /**
   * Encodes a parameter value using the codec registered for the given PgType
   * and binds it, choosing binary or text wire format based on
   * {@link org.postgresql.core.BaseConnection#binaryTransferSend(int)}.
   *
   * <p>The actual codec is resolved from {@link CodecRegistry} via the type's
   * OID, so a custom codec registered by the caller (e.g. via SPI) is honored
   * transparently. {@link CodecRegistry} guarantees a non-null codec
   * (FallbackCodec for unknown types).</p>
   *
   * @param parameterIndex the parameter index (1-based)
   * @param value the value to encode
   * @param pgType resolved type metadata
   * @throws SQLException if no codec for the requested wire format is available
   *     or if encoding/binding fails
   */
  private void bindViaCodec(@Positive int parameterIndex,
      Object value, PgType pgType) throws SQLException {
    int oid = pgType.getOid();
    CodecContext ctx = connection.getCodecContext();
    CodecRegistry codecs = ctx.getCodecs();
    if (connection.binaryTransferSend(oid)) {
      BinaryCodec codec = codecs.getBinaryCodec(oid, pgType);
      if (codec == null) {
        throw new PSQLException(
            GT.tr("No binary codec registered for type {0}", pgType.getTypeName()),
            PSQLState.SYSTEM_ERROR);
      }
      bindBytes(parameterIndex, codec.encodeBinary(value, pgType, ctx), oid);
      return;
    }
    TextCodec codec = codecs.getTextCodec(oid, pgType);
    if (codec == null) {
      throw new PSQLException(
          GT.tr("No text codec registered for type {0}", pgType.getTypeName()),
          PSQLState.SYSTEM_ERROR);
    }
    bindLiteral(parameterIndex, codec.encodeText(value, pgType, ctx), oid);
  }

  @Override
  public void setRowId(@Positive int parameterIndex, @Nullable RowId x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setRowId(int, RowId)");
  }

  @Override
  public void setNString(@Positive int parameterIndex, @Nullable String value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNString(int, String)");
  }

  @Override
  public void setNCharacterStream(@Positive int parameterIndex, @Nullable Reader value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNCharacterStream(int, Reader, long)");
  }

  @Override
  public void setNCharacterStream(@Positive int parameterIndex,
      @Nullable Reader value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNCharacterStream(int, Reader)");
  }

  @Override
  public void setCharacterStream(@Positive int parameterIndex,
      @Nullable Reader value, @NonNegative long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setCharacterStream(int, Reader, long)");
  }

  @Override
  public void setCharacterStream(@Positive int parameterIndex,
      @Nullable Reader value) throws SQLException {
    if (connection.getPreferQueryMode() == PreferQueryMode.SIMPLE) {
      String s = value != null ? readerToString(value, Integer.MAX_VALUE) : null;
      setString(parameterIndex, s);
      return;
    }
    InputStream is = value != null ? new ReaderInputStream(value) : null;
    setObject(parameterIndex, is, Types.LONGVARCHAR);
  }

  @Override
  public void setBinaryStream(@Positive int parameterIndex, @Nullable InputStream value,
      @NonNegative @IntRange(from = 0, to = Integer.MAX_VALUE) long length)
      throws SQLException {
    checkClosed();

    if (value == null) {
      setNull(parameterIndex, Types.VARBINARY);
      return;
    }

    //noinspection ConstantConditions
    if (length > Integer.MAX_VALUE) {
      throw new PSQLException(GT.tr("Object is too large to send over the protocol."),
          PSQLState.NUMERIC_CONSTANT_OUT_OF_RANGE);
    } else if (length < 0) {
      throw new PSQLException(GT.tr("Invalid stream length {0}.", length),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    preparedParameters.setBytea(parameterIndex, value, (int) length);
  }

  @Override
  public void setBinaryStream(@Positive int parameterIndex,
      @Nullable InputStream value) throws SQLException {
    if (value == null) {
      preparedParameters.setNull(parameterIndex, Oid.BYTEA);
    } else {
      preparedParameters.setBytea(parameterIndex, value);
    }
  }

  @Override
  public void setAsciiStream(@Positive int parameterIndex,
      @Nullable InputStream value, @NonNegative long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setAsciiStream(int, InputStream, long)");
  }

  @Override
  public void setAsciiStream(@Positive int parameterIndex,
      @Nullable InputStream value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setAsciiStream(int, InputStream)");
  }

  @Override
  public void setNClob(@Positive int parameterIndex,
      @Nullable NClob value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(int, NClob)");
  }

  @Override
  public void setClob(@Positive int parameterIndex,
      @Nullable Reader reader, @NonNegative long length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setClob(int, Reader, long)");
  }

  @Override
  public void setClob(@Positive int parameterIndex,
      @Nullable Reader reader) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setClob(int, Reader)");
  }

  @Override
  public void setBlob(@Positive int parameterIndex,
      @Nullable InputStream inputStream, @NonNegative long length)
      throws SQLException {
    checkClosed();

    if (inputStream == null) {
      setNull(parameterIndex, Types.BLOB);
      return;
    }

    //noinspection ConstantConditions
    if (length < 0) {
      throw new PSQLException(GT.tr("Invalid stream length {0}.", length),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    long oid = createBlob(inputStream, length);
    setLong(parameterIndex, oid);
  }

  @Override
  public void setBlob(@Positive int parameterIndex,
      @Nullable InputStream inputStream) throws SQLException {
    checkClosed();

    if (inputStream == null) {
      setNull(parameterIndex, Types.BLOB);
      return;
    }

    long oid = createBlob(inputStream, Long.MAX_VALUE);
    setLong(parameterIndex, oid);
  }

  @Override
  public void setNClob(@Positive int parameterIndex,
      @Nullable Reader reader, @NonNegative long length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(int, Reader, long)");
  }

  @Override
  public void setNClob(@Positive int parameterIndex,
      @Nullable Reader reader) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(int, Reader)");
  }

  @Override
  public void setSQLXML(@Positive int parameterIndex,
      @Nullable SQLXML xmlObject) throws SQLException {
    checkClosed();
    String stringValue = xmlObject == null ? null : xmlObject.getString();
    if (stringValue == null) {
      setNull(parameterIndex, Types.SQLXML);
    } else {
      setString(parameterIndex, stringValue, Oid.XML);
    }
  }

  private void setUuid(@Positive int parameterIndex, UUID uuid) throws SQLException {
    bindViaCodec(parameterIndex, uuid, connection.getTypeInfo().getPgTypeByOid(Oid.UUID));
  }

  @Override
  public void setURL(@Positive int parameterIndex, @Nullable URL x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setURL(int,URL)");
  }

  @Override
  public int[] executeBatch() throws SQLException {
    try {
      // Note: in batch prepared statements batchStatements == 1, and batchParameters is equal
      // to the number of addBatch calls
      // batchParameters might be empty in case of empty batch
      if (batchParameters != null && batchParameters.size() > 1 && mPrepareThreshold > 0) {
        // Use server-prepared statements when there's more than one statement in a batch
        // Technically speaking, it might cause to create a server-prepared statement
        // just for 2 executions even for prepareThreshold=5. That however should be
        // acceptable since prepareThreshold is a optimization kind of parameter.
        this.preparedQuery.increaseExecuteCount(mPrepareThreshold);
      }
      return super.executeBatch();
    } finally {
      getDateTimeHelper().resetDefaultTimeZone();
    }
  }

  private Calendar getDefaultCalendar() {
    return getDateTimeHelper().getDefaultCalendar();
  }

  @Override
  public ParameterMetaData getParameterMetaData() throws SQLException {
    int flags = QueryExecutor.QUERY_ONESHOT | QueryExecutor.QUERY_DESCRIBE_ONLY
        | QueryExecutor.QUERY_SUPPRESS_BEGIN;
    ResultHandler handler = new DiscardResultHandler();
    connection.getQueryExecutor().execute(preparedQuery.query, preparedParameters, handler, 0, 0,
        flags);

    int[] oids = preparedParameters.getTypeOIDs();
    return createParameterMetaData(connection, oids);
  }

  @Override
  protected void transformQueriesAndParameters() throws SQLException {
    ArrayList<@Nullable ParameterList> batchParameters = this.batchParameters;
    if (batchParameters == null || batchParameters.size() <= 1
        || !(preparedQuery.query instanceof BatchedQuery)) {
      return;
    }
    BatchedQuery originalQuery = (BatchedQuery) preparedQuery.query;
    // Cap the rows merged into one multi-values INSERT. deriveForMultiBatch only accepts power-of-two
    // blocks up to BatchedQuery.MAX_VALUE_BLOCK, so that is the row ceiling in every query mode.
    // The extended protocol additionally limits a statement to maximumNumberOfParameters() (65535)
    // bind values, hence min(.../bindCount, ceiling); the simple protocol inlines parameters and has
    // no such limit (maximumNumberOfParameters() is Integer.MAX_VALUE there), so only the ceiling
    // applies. reWriteBatchedInsertsSize lowers the ceiling. The result is rounded down to a power of
    // two, and the batch is split into that many rows per statement.
    final int bindCount = originalQuery.getBindCount();
    final int configuredSize = connection.getQueryExecutor().getReWriteBatchedInsertsSize();
    final int rowCeiling = configuredSize > 0
        ? Math.min(configuredSize, BatchedQuery.MAX_VALUE_BLOCK)
        : BatchedQuery.MAX_VALUE_BLOCK;
    final int maxValueBlocks;
    if (bindCount == 0) {
      // No binds means no protocol limit; default to 1024 rows unless a smaller cap is configured.
      maxValueBlocks = Integer.highestOneBit(Math.max(1, configuredSize > 0 ? rowCeiling : 1024));
    } else {
      maxValueBlocks = Integer.highestOneBit(
          Math.max(1, Math.min(maximumNumberOfParameters() / bindCount, rowCeiling)));
    }
    int unprocessedBatchCount = batchParameters.size();
    final int fullValueBlocksCount = unprocessedBatchCount / maxValueBlocks;
    final int partialValueBlocksCount = Integer.bitCount(unprocessedBatchCount % maxValueBlocks);
    final int count = fullValueBlocksCount + partialValueBlocksCount;
    ArrayList<Query> newBatchStatements = new ArrayList<>(count);
    ArrayList<@Nullable ParameterList> newBatchParameters =
        new ArrayList<@Nullable ParameterList>(count);
    int offset = 0;
    for (int i = 0; i < count; i++) {
      int valueBlock;
      if (unprocessedBatchCount >= maxValueBlocks) {
        valueBlock = maxValueBlocks;
      } else {
        valueBlock = Integer.highestOneBit(unprocessedBatchCount);
      }
      // Find appropriate batch for block count.
      BatchedQuery bq = originalQuery.deriveForMultiBatch(valueBlock);
      ParameterList newPl = bq.createParameterList();
      for (int j = 0; j < valueBlock; j++) {
        ParameterList pl = batchParameters.get(offset++);
        if (pl != null) {
          newPl.appendAll(pl);
        }
      }
      newBatchStatements.add(bq);
      newBatchParameters.add(newPl);
      unprocessedBatchCount -= valueBlock;
    }
    this.batchStatements = newBatchStatements;
    this.batchParameters = newBatchParameters;
  }
}
