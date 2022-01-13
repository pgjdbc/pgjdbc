/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.Driver;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.CachedQuery;
import org.postgresql.core.Oid;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.TypeInfo;
import org.postgresql.core.v3.BatchedQuery;
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
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.common.value.qual.IntRange;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

class PgPreparedStatement extends PgStatement implements PreparedStatement {

  protected final CachedQuery preparedQuery; // Query fragments for prepared statement.
  protected final ParameterList preparedParameters; // Parameter values for prepared statement.

  private @Nullable TimeZone defaultTimeZone;

  PgPreparedStatement(PgConnection connection, String sql, int rsType, int rsConcurrency,
      int rsHoldability) throws SQLException {
    this(connection, connection.borrowQuery(sql), rsType, rsConcurrency, rsHoldability);
  }

  @SuppressWarnings("method.invocation.invalid")
  PgPreparedStatement(PgConnection connection, CachedQuery query, int rsType,
      int rsConcurrency, int rsHoldability) throws SQLException {
    super(connection, rsType, rsConcurrency, rsHoldability);

    this.preparedQuery = query;
    this.preparedParameters = this.preparedQuery.query.createParameterList();
    // TODO: this.wantsGeneratedKeysAlways = true;

    setPoolable(true); // As per JDBC spec: prepared and callable statements are poolable by
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    throw new PSQLException(
        GT.tr("Can''t use query methods that take a query string on a PreparedStatement."),
        PSQLState.WRONG_OBJECT_TYPE);
  }

  /*
   * A Prepared SQL query is executed and its ResultSet is returned
   *
   * @return a ResultSet that contains the data produced by the * query - never null
   *
   * @exception SQLException if a database access error occurs
   */
  @Override
  public ResultSet executeQuery() throws SQLException {
    if (!executeWithFlags(0)) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    return getSingleResultSet();
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    throw new PSQLException(
        GT.tr("Can''t use query methods that take a query string on a PreparedStatement."),
        PSQLState.WRONG_OBJECT_TYPE);
  }

  @Override
  public int executeUpdate() throws SQLException {
    executeWithFlags(QueryExecutor.QUERY_NO_RESULTS);
    checkNoResultUpdate();
    return getUpdateCount();
  }

  @Override
  public long executeLargeUpdate() throws SQLException {
    executeWithFlags(QueryExecutor.QUERY_NO_RESULTS);
    checkNoResultUpdate();
    return getLargeUpdateCount();
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    throw new PSQLException(
        GT.tr("Can''t use query methods that take a query string on a PreparedStatement."),
        PSQLState.WRONG_OBJECT_TYPE);
  }

  @Override
  public boolean execute() throws SQLException {
    return executeWithFlags(0);
  }

  public boolean executeWithFlags(int flags) throws SQLException {
    try {
      checkClosed();

      if (connection.getPreferQueryMode() == PreferQueryMode.SIMPLE) {
        flags |= QueryExecutor.QUERY_EXECUTE_AS_SIMPLE;
      }

      execute(preparedQuery, preparedParameters, flags);

      synchronized (this) {
        checkClosed();
        return (result != null && result.getResultSet() != null);
      }
    } finally {
      defaultTimeZone = null;
    }
  }

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

  public void setNull(int parameterIndex, int sqlType) throws SQLException {
    checkClosed();

    if (parameterIndex < 1 || parameterIndex > preparedParameters.getParameterCount()) {
      throw new PSQLException(
        GT.tr("The column index is out of range: {0}, number of columns: {1}.",
          parameterIndex, preparedParameters.getParameterCount()),
        PSQLState.INVALID_PARAMETER_VALUE);
    }

    int oid;
    switch (sqlType) {
      case Types.SQLXML:
        oid = Oid.XML;
        break;
      case Types.INTEGER:
        oid = Oid.INT4;
        break;
      case Types.TINYINT:
      case Types.SMALLINT:
        oid = Oid.INT2;
        break;
      case Types.BIGINT:
        oid = Oid.INT8;
        break;
      case Types.REAL:
        oid = Oid.FLOAT4;
        break;
      case Types.DOUBLE:
      case Types.FLOAT:
        oid = Oid.FLOAT8;
        break;
      case Types.DECIMAL:
      case Types.NUMERIC:
        oid = Oid.NUMERIC;
        break;
      case Types.CHAR:
        oid = Oid.BPCHAR;
        break;
      case Types.VARCHAR:
      case Types.LONGVARCHAR:
        oid = connection.getStringVarcharFlag() ? Oid.VARCHAR : Oid.UNSPECIFIED;
        break;
      case Types.DATE:
        oid = Oid.DATE;
        break;
      case Types.TIME:
      case Types.TIME_WITH_TIMEZONE:
      case Types.TIMESTAMP_WITH_TIMEZONE:
      case Types.TIMESTAMP:
        oid = Oid.UNSPECIFIED;
        break;
      case Types.BOOLEAN:
      case Types.BIT:
        oid = Oid.BOOL;
        break;
      case Types.BINARY:
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
        oid = Oid.BYTEA;
        break;
      case Types.BLOB:
      case Types.CLOB:
        oid = Oid.OID;
        break;
      case Types.REF_CURSOR:
        oid = Oid.REF_CURSOR;
        break;
      case Types.ARRAY:
      case Types.DISTINCT:
      case Types.STRUCT:
      case Types.NULL:
      case Types.OTHER:
        oid = Oid.UNSPECIFIED;
        break;
      default:
        // Bad Types value.
        throw new PSQLException(GT.tr("Unknown Types value."), PSQLState.INVALID_PARAMETER_TYPE);
    }
    preparedParameters.setNull(parameterIndex, oid);
  }

  public void setBoolean(@Positive int parameterIndex, boolean x) throws SQLException {
    checkClosed();
    // The key words TRUE and FALSE are the preferred (SQL-compliant) usage.
    bindLiteral(parameterIndex, x ? "TRUE" : "FALSE", Oid.BOOL);
  }

  public void setByte(@Positive int parameterIndex, byte x) throws SQLException {
    setShort(parameterIndex, x);
  }

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

  public void setBigDecimal(@Positive int parameterIndex, @Nullable BigDecimal x)
      throws SQLException {
    if (x != null && connection.binaryTransferSend(Oid.NUMERIC)) {
      final byte[] bytes = ByteConverter.numeric(x);
      bindBytes(parameterIndex, bytes, Oid.NUMERIC);
      return;
    }
    setNumber(parameterIndex, x);
  }

  public void setString(@Positive int parameterIndex, @Nullable String x) throws SQLException {
    checkClosed();
    setString(parameterIndex, x, getStringType());
  }

  private int getStringType() {
    return (connection.getStringVarcharFlag() ? Oid.VARCHAR : Oid.UNSPECIFIED);
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

  public void setDate(@Positive int parameterIndex,
      java.sql.@Nullable Date x) throws SQLException {
    setDate(parameterIndex, x, null);
  }

  public void setTime(@Positive int parameterIndex, @Nullable Time x) throws SQLException {
    setTime(parameterIndex, x, null);
  }

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
      InputStreamReader inStream = new InputStreamReader(x, encoding);
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
    } catch (UnsupportedEncodingException uee) {
      throw new PSQLException(GT.tr("The JVM claims not to support the {0} encoding.", encoding),
          PSQLState.UNEXPECTED_ERROR, uee);
    } catch (IOException ioe) {
      throw new PSQLException(GT.tr("Provided InputStream failed."), PSQLState.UNEXPECTED_ERROR,
          ioe);
    }
  }

  public void setAsciiStream(@Positive int parameterIndex, @Nullable InputStream x,
      @NonNegative int length) throws SQLException {
    checkClosed();
    setCharacterStreamPost71(parameterIndex, x, length, "ASCII");
  }

  public void setUnicodeStream(@Positive int parameterIndex, @Nullable InputStream x,
      @NonNegative int length) throws SQLException {
    checkClosed();

    setCharacterStreamPost71(parameterIndex, x, length, "UTF-8");
  }

  public void setBinaryStream(@Positive int parameterIndex, @Nullable InputStream x,
      @NonNegative int length) throws SQLException {
    checkClosed();

    if (x == null) {
      setNull(parameterIndex, Types.VARBINARY);
      return;
    }

    if (length < 0) {
      throw new PSQLException(GT.tr("Invalid stream length {0}.", length),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    // Version 7.2 supports BinaryStream for for the PG bytea type
    // As the spec/javadoc for this method indicate this is to be used for
    // large binary values (i.e. LONGVARBINARY) PG doesn't have a separate
    // long binary datatype, but with toast the bytea datatype is capable of
    // handling very large values.
    preparedParameters.setBytea(parameterIndex, x, length);
  }

  public void clearParameters() throws SQLException {
    preparedParameters.clear();
  }

  // Helper method for setting parameters to PGobject subclasses.
  private void setPGobject(@Positive int parameterIndex, PGobject x) throws SQLException {
    String typename = x.getType();
    int oid = connection.getTypeInfo().getPGType(typename);
    if (oid == Oid.UNSPECIFIED) {
      throw new PSQLException(GT.tr("Unknown type {0}.", typename),
          PSQLState.INVALID_PARAMETER_TYPE);
    }

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
    int oid = connection.getTypeInfo().getPGType("hstore");
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
          preparedParameters.setText(parameterIndex, (InputStream)in);
        } else {
          setString(parameterIndex, castToString(in), getStringType());
        }
        break;
      case Types.DATE:
        if (in instanceof java.sql.Date) {
          setDate(parameterIndex, (java.sql.Date) in);
        } else {
          java.sql.Date tmpd;
          if (in instanceof java.util.Date) {
            tmpd = new java.sql.Date(((java.util.Date) in).getTime());
          } else if (in instanceof LocalDate) {
            setDate(parameterIndex, (LocalDate) in);
            break;
          } else {
            tmpd = getTimestampUtils().toDate(getDefaultCalendar(), in.toString());
          }
          setDate(parameterIndex, tmpd);
        }
        break;
      case Types.TIME:
        if (in instanceof java.sql.Time) {
          setTime(parameterIndex, (java.sql.Time) in);
        } else {
          java.sql.Time tmpt;
          if (in instanceof java.util.Date) {
            tmpt = new java.sql.Time(((java.util.Date) in).getTime());
          } else if (in instanceof LocalTime) {
            setTime(parameterIndex, (LocalTime) in);
            break;
          } else {
            tmpt = getTimestampUtils().toTime(getDefaultCalendar(), in.toString());
          }
          setTime(parameterIndex, tmpt);
        }
        break;
      case Types.TIMESTAMP:
        if (in instanceof PGTimestamp) {
          setObject(parameterIndex, in);
        } else if (in instanceof java.sql.Timestamp) {
          setTimestamp(parameterIndex, (java.sql.Timestamp) in);
        } else {
          java.sql.Timestamp tmpts;
          if (in instanceof java.util.Date) {
            tmpts = new java.sql.Timestamp(((java.util.Date) in).getTime());
          } else if (in instanceof LocalDateTime) {
            setTimestamp(parameterIndex, (LocalDateTime) in);
            break;
          } else {
            tmpts = getTimestampUtils().toTimestamp(getDefaultCalendar(), in.toString());
          }
          setTimestamp(parameterIndex, tmpts);
        }
        break;
      case Types.TIMESTAMP_WITH_TIMEZONE:
        if (in instanceof OffsetDateTime) {
          setTimestamp(parameterIndex, (OffsetDateTime) in);
        } else if (in instanceof PGTimestamp) {
          setObject(parameterIndex, in);
        } else {
          throw new PSQLException(
              GT.tr("Cannot cast an instance of {0} to type {1}",
                  in.getClass().getName(), "Types.TIMESTAMP_WITH_TIMEZONE"),
              PSQLState.INVALID_PARAMETER_TYPE);
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
          long oid = createBlob(parameterIndex, (InputStream) in, -1);
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

  private <A extends @NonNull Object> void setObjectArray(int parameterIndex, A in) throws SQLException {
    final ArrayEncoding.ArrayEncoder<A> arraySupport = ArrayEncoding.getArrayEncoder(in);

    final TypeInfo typeInfo = connection.getTypeInfo();

    final int oid = arraySupport.getDefaultArrayTypeOid();

    if (arraySupport.supportBinaryRepresentation(oid) && connection.getPreferQueryMode() != PreferQueryMode.SIMPLE) {
      bindBytes(parameterIndex, arraySupport.toBinaryRepresentation(connection, in, oid), oid);
    } else {
      if (oid == Oid.UNSPECIFIED) {
        throw new SQLFeatureNotSupportedException();
      }
      final int baseOid = typeInfo.getPGArrayElement(oid);
      final String baseType = castNonNull(typeInfo.getPGType(baseOid));

      final Array array = getPGConnection().createArrayOf(baseType, in);
      this.setArray(parameterIndex, array);
    }
  }

  private static String asString(final Clob in) throws SQLException {
    return in.getSubString(1, (int) in.length());
  }

  private static int castToInt(final Object in) throws SQLException {
    try {
      if (in instanceof String) {
        return Integer.parseInt((String) in);
      }
      if (in instanceof Number) {
        return ((Number) in).intValue();
      }
      if (in instanceof java.util.Date) {
        return (int) ((java.util.Date) in).getTime();
      }
      if (in instanceof Boolean) {
        return (Boolean) in ? 1 : 0;
      }
      if (in instanceof Clob) {
        return Integer.parseInt(asString((Clob) in));
      }
      if (in instanceof Character) {
        return Integer.parseInt(in.toString());
      }
    } catch (final Exception e) {
      throw cannotCastException(in.getClass().getName(), "int", e);
    }
    throw cannotCastException(in.getClass().getName(), "int");
  }

  private static short castToShort(final Object in) throws SQLException {
    try {
      if (in instanceof String) {
        return Short.parseShort((String) in);
      }
      if (in instanceof Number) {
        return ((Number) in).shortValue();
      }
      if (in instanceof java.util.Date) {
        return (short) ((java.util.Date) in).getTime();
      }
      if (in instanceof Boolean) {
        return (Boolean) in ? (short) 1 : (short) 0;
      }
      if (in instanceof Clob) {
        return Short.parseShort(asString((Clob) in));
      }
      if (in instanceof Character) {
        return Short.parseShort(in.toString());
      }
    } catch (final Exception e) {
      throw cannotCastException(in.getClass().getName(), "short", e);
    }
    throw cannotCastException(in.getClass().getName(), "short");
  }

  private static long castToLong(final Object in) throws SQLException {
    try {
      if (in instanceof String) {
        return Long.parseLong((String) in);
      }
      if (in instanceof Number) {
        return ((Number) in).longValue();
      }
      if (in instanceof java.util.Date) {
        return ((java.util.Date) in).getTime();
      }
      if (in instanceof Boolean) {
        return (Boolean) in ? 1L : 0L;
      }
      if (in instanceof Clob) {
        return Long.parseLong(asString((Clob) in));
      }
      if (in instanceof Character) {
        return Long.parseLong(in.toString());
      }
    } catch (final Exception e) {
      throw cannotCastException(in.getClass().getName(), "long", e);
    }
    throw cannotCastException(in.getClass().getName(), "long");
  }

  private static float castToFloat(final Object in) throws SQLException {
    try {
      if (in instanceof String) {
        return Float.parseFloat((String) in);
      }
      if (in instanceof Number) {
        return ((Number) in).floatValue();
      }
      if (in instanceof java.util.Date) {
        return ((java.util.Date) in).getTime();
      }
      if (in instanceof Boolean) {
        return (Boolean) in ? 1f : 0f;
      }
      if (in instanceof Clob) {
        return Float.parseFloat(asString((Clob) in));
      }
      if (in instanceof Character) {
        return Float.parseFloat(in.toString());
      }
    } catch (final Exception e) {
      throw cannotCastException(in.getClass().getName(), "float", e);
    }
    throw cannotCastException(in.getClass().getName(), "float");
  }

  private static double castToDouble(final Object in) throws SQLException {
    try {
      if (in instanceof String) {
        return Double.parseDouble((String) in);
      }
      if (in instanceof Number) {
        return ((Number) in).doubleValue();
      }
      if (in instanceof java.util.Date) {
        return ((java.util.Date) in).getTime();
      }
      if (in instanceof Boolean) {
        return (Boolean) in ? 1d : 0d;
      }
      if (in instanceof Clob) {
        return Double.parseDouble(asString((Clob) in));
      }
      if (in instanceof Character) {
        return Double.parseDouble(in.toString());
      }
    } catch (final Exception e) {
      throw cannotCastException(in.getClass().getName(), "double", e);
    }
    throw cannotCastException(in.getClass().getName(), "double");
  }

  private static BigDecimal castToBigDecimal(final Object in, final int scale) throws SQLException {
    try {
      BigDecimal rc = null;
      if (in instanceof String) {
        rc = new BigDecimal((String) in);
      } else if (in instanceof BigDecimal) {
        rc = ((BigDecimal) in);
      } else if (in instanceof BigInteger) {
        rc = new BigDecimal((BigInteger) in);
      } else if (in instanceof Long || in instanceof Integer || in instanceof Short
          || in instanceof Byte) {
        rc = BigDecimal.valueOf(((Number) in).longValue());
      } else if (in instanceof Double || in instanceof Float) {
        rc = BigDecimal.valueOf(((Number) in).doubleValue());
      } else if (in instanceof java.util.Date) {
        rc = BigDecimal.valueOf(((java.util.Date) in).getTime());
      } else if (in instanceof Boolean) {
        rc = (Boolean) in ? BigDecimal.ONE : BigDecimal.ZERO;
      } else if (in instanceof Clob) {
        rc = new BigDecimal(asString((Clob) in));
      } else if (in instanceof Character) {
        rc = new BigDecimal(new char[]{(Character) in});
      }
      if (rc != null) {
        if (scale >= 0) {
          rc = rc.setScale(scale, RoundingMode.HALF_UP);
        }
        return rc;
      }
    } catch (final Exception e) {
      throw cannotCastException(in.getClass().getName(), "BigDecimal", e);
    }
    throw cannotCastException(in.getClass().getName(), "BigDecimal");
  }

  private static String castToString(final Object in) throws SQLException {
    try {
      if (in instanceof String) {
        return (String) in;
      }
      if (in instanceof Clob) {
        return asString((Clob) in);
      }
      // convert any unknown objects to string.
      return in.toString();

    } catch (final Exception e) {
      throw cannotCastException(in.getClass().getName(), "String", e);
    }
  }

  private static PSQLException cannotCastException(final String fromType, final String toType) {
    return cannotCastException(fromType, toType, null);
  }

  private static PSQLException cannotCastException(final String fromType, final String toType,
      final @Nullable Exception cause) {
    return new PSQLException(
        GT.tr("Cannot convert an instance of {0} to type {1}", fromType, toType),
        PSQLState.INVALID_PARAMETER_TYPE, cause);
  }

  public void setObject(@Positive int parameterIndex, @Nullable Object x,
      int targetSqlType) throws SQLException {
    setObject(parameterIndex, x, targetSqlType, -1);
  }

  /*
   * This stores an Object into a parameter.
   */
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
    } else if (x instanceof java.sql.Date) {
      setDate(parameterIndex, (java.sql.Date) x);
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
    } else if (x instanceof PGobject) {
      setPGobject(parameterIndex, (PGobject) x);
    } else if (x instanceof Character) {
      setString(parameterIndex, ((Character) x).toString());
    } else if (x instanceof LocalDate) {
      setDate(parameterIndex, (LocalDate) x);
    } else if (x instanceof LocalTime) {
      setTime(parameterIndex, (LocalTime) x);
    } else if (x instanceof LocalDateTime) {
      setTimestamp(parameterIndex, (LocalDateTime) x);
    } else if (x instanceof OffsetDateTime) {
      setTimestamp(parameterIndex, (OffsetDateTime) x);
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
  public String toString() {
    if (preparedQuery == null) {
      return super.toString();
    }

    return preparedQuery.query.toString(preparedParameters);
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

  @Override
  public boolean isUseServerPrepare() {
    return (preparedQuery != null && mPrepareThreshold != 0
        && preparedQuery.getExecuteCount() + 1 >= mPrepareThreshold);
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
      this.batchStatements = batchStatements = new ArrayList<Query>();
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

  public @Nullable ResultSetMetaData getMetaData() throws SQLException {
    checkClosed();
    ResultSet rs = getResultSet();

    if (rs == null || ((PgResultSet) rs).isResultSetClosed()) {
      // OK, we haven't executed it yet, or it was closed
      // we've got to go to the backend
      // for more info. We send the full query, but just don't
      // execute it.

      int flags = QueryExecutor.QUERY_ONESHOT | QueryExecutor.QUERY_DESCRIBE_ONLY
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

  public void setArray(int i, java.sql.@Nullable Array x) throws SQLException {
    checkClosed();

    if (null == x) {
      setNull(i, Types.ARRAY);
      return;
    }

    // This only works for Array implementations that return a valid array
    // literal from Array.toString(), such as the implementation we return
    // from ResultSet.getArray(). Eventually we need a proper implementation
    // here that works for any Array implementation.
    String typename = x.getBaseTypeName();
    int oid = connection.getTypeInfo().getPGArrayType(typename);
    if (oid == Oid.UNSPECIFIED) {
      throw new PSQLException(GT.tr("Unknown type {0}.", typename),
          PSQLState.INVALID_PARAMETER_TYPE);
    }

    if (x instanceof PgArray) {
      PgArray arr = (PgArray) x;
      byte[] bytes = arr.toBytes();
      if (bytes != null) {
        bindBytes(i, bytes, oid);
        return;
      }
    }

    setString(i, x.toString(), oid);
  }

  protected long createBlob(int i, InputStream inputStream,
      @NonNegative long length) throws SQLException {
    LargeObjectManager lom = connection.getLargeObjectAPI();
    long oid = lom.createLO();
    LargeObject lob = lom.open(oid);
    OutputStream outputStream = lob.getOutputStream();
    byte[] buf = new byte[4096];
    try {
      long remaining;
      if (length > 0) {
        remaining = length;
      } else {
        remaining = Long.MAX_VALUE;
      }
      int numRead = inputStream.read(buf, 0,
          (length > 0 && remaining < buf.length ? (int) remaining : buf.length));
      while (numRead != -1 && remaining > 0) {
        remaining -= numRead;
        outputStream.write(buf, 0, numRead);
        numRead = inputStream.read(buf, 0,
            (length > 0 && remaining < buf.length ? (int) remaining : buf.length));
      }
    } catch (IOException se) {
      throw new PSQLException(GT.tr("Unexpected error writing large object to database."),
          PSQLState.UNEXPECTED_ERROR, se);
    } finally {
      try {
        outputStream.close();
      } catch (Exception e) {
      }
    }
    return oid;
  }

  public void setBlob(@Positive int i, @Nullable Blob x) throws SQLException {
    checkClosed();

    if (x == null) {
      setNull(i, Types.BLOB);
      return;
    }

    InputStream inStream = x.getBinaryStream();
    try {
      long oid = createBlob(i, inStream, x.length());
      setLong(i, oid);
    } finally {
      try {
        inStream.close();
      } catch (Exception e) {
      }
    }
  }

  private String readerToString(Reader value, int maxLength) throws SQLException {
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

  public void setCharacterStream(@Positive int i, java.io.@Nullable Reader x,
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

    // Version 7.2 supports CharacterStream for for the PG text types
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

  public void setNull(@Positive int parameterIndex, int t,
      @Nullable String typeName) throws SQLException {
    if (typeName == null) {
      setNull(parameterIndex, t);
      return;
    }

    checkClosed();

    TypeInfo typeInfo = connection.getTypeInfo();
    int oid = typeInfo.getPGType(typeName);

    preparedParameters.setNull(parameterIndex, oid);
  }

  public void setRef(@Positive int i, @Nullable Ref x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setRef(int,Ref)");
  }

  public void setDate(@Positive int i, java.sql.@Nullable Date d,
      java.util.@Nullable Calendar cal) throws SQLException {
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

  public void setTime(@Positive int i, @Nullable Time t,
      java.util.@Nullable Calendar cal) throws SQLException {
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

  public void setTimestamp(@Positive int i, @Nullable Timestamp t,
      java.util.@Nullable Calendar cal) throws SQLException {
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

  public void setObject(@Positive int parameterIndex, @Nullable Object x,
      java.sql.SQLType targetSqlType,
      int scaleOrLength) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject");
  }

  public void setObject(@Positive int parameterIndex, @Nullable Object x,
      java.sql.SQLType targetSqlType)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject");
  }

  public void setRowId(@Positive int parameterIndex, @Nullable RowId x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setRowId(int, RowId)");
  }

  public void setNString(@Positive int parameterIndex, @Nullable String value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNString(int, String)");
  }

  public void setNCharacterStream(@Positive int parameterIndex, @Nullable Reader value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNCharacterStream(int, Reader, long)");
  }

  public void setNCharacterStream(@Positive int parameterIndex,
      @Nullable Reader value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNCharacterStream(int, Reader)");
  }

  public void setCharacterStream(@Positive int parameterIndex,
      @Nullable Reader value, @NonNegative long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setCharacterStream(int, Reader, long)");
  }

  public void setCharacterStream(@Positive int parameterIndex,
      @Nullable Reader value) throws SQLException {
    if (connection.getPreferQueryMode() == PreferQueryMode.SIMPLE) {
      String s = (value != null) ? readerToString(value, Integer.MAX_VALUE) : null;
      setString(parameterIndex, s);
      return;
    }
    InputStream is = (value != null) ? new ReaderInputStream(value) : null;
    setObject(parameterIndex, is, Types.LONGVARCHAR);
  }

  public void setBinaryStream(@Positive int parameterIndex, @Nullable InputStream value,
      @NonNegative @IntRange(from = 0, to = Integer.MAX_VALUE) long length)
      throws SQLException {
    //noinspection ConstantConditions
    if (length > Integer.MAX_VALUE) {
      throw new PSQLException(GT.tr("Object is too large to send over the protocol."),
          PSQLState.NUMERIC_CONSTANT_OUT_OF_RANGE);
    }
    if (value == null) {
      preparedParameters.setNull(parameterIndex, Oid.BYTEA);
    } else {
      preparedParameters.setBytea(parameterIndex, value, (int) length);
    }
  }

  public void setBinaryStream(@Positive int parameterIndex,
      @Nullable InputStream value) throws SQLException {
    if (value == null) {
      preparedParameters.setNull(parameterIndex, Oid.BYTEA);
    } else {
      preparedParameters.setBytea(parameterIndex, value);
    }
  }

  public void setAsciiStream(@Positive int parameterIndex,
      @Nullable InputStream value, @NonNegative long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setAsciiStream(int, InputStream, long)");
  }

  public void setAsciiStream(@Positive int parameterIndex,
      @Nullable InputStream value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setAsciiStream(int, InputStream)");
  }

  public void setNClob(@Positive int parameterIndex,
      @Nullable NClob value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(int, NClob)");
  }

  public void setClob(@Positive int parameterIndex,
      @Nullable Reader reader, @NonNegative long length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setClob(int, Reader, long)");
  }

  public void setClob(@Positive int parameterIndex,
      @Nullable Reader reader) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setClob(int, Reader)");
  }

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

    long oid = createBlob(parameterIndex, inputStream, length);
    setLong(parameterIndex, oid);
  }

  public void setBlob(@Positive int parameterIndex,
      @Nullable InputStream inputStream) throws SQLException {
    checkClosed();

    if (inputStream == null) {
      setNull(parameterIndex, Types.BLOB);
      return;
    }

    long oid = createBlob(parameterIndex, inputStream, -1);
    setLong(parameterIndex, oid);
  }

  public void setNClob(@Positive int parameterIndex,
      @Nullable Reader reader, @NonNegative long length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(int, Reader, long)");
  }

  public void setNClob(@Positive int parameterIndex,
      @Nullable Reader reader) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(int, Reader)");
  }

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
    if (connection.binaryTransferSend(Oid.UUID)) {
      byte[] val = new byte[16];
      ByteConverter.int8(val, 0, uuid.getMostSignificantBits());
      ByteConverter.int8(val, 8, uuid.getLeastSignificantBits());
      bindBytes(parameterIndex, val, Oid.UUID);
    } else {
      bindLiteral(parameterIndex, uuid.toString(), Oid.UUID);
    }
  }

  public void setURL(@Positive int parameterIndex, java.net.@Nullable URL x) throws SQLException {
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
      defaultTimeZone = null;
    }
  }

  private Calendar getDefaultCalendar() {
    if (getTimestampUtils().hasFastDefaultTimeZone()) {
      return getTimestampUtils().getSharedCalendar(null);
    }
    Calendar sharedCalendar = getTimestampUtils().getSharedCalendar(defaultTimeZone);
    if (defaultTimeZone == null) {
      defaultTimeZone = sharedCalendar.getTimeZone();
    }
    return sharedCalendar;
  }

  public ParameterMetaData getParameterMetaData() throws SQLException {
    int flags = QueryExecutor.QUERY_ONESHOT | QueryExecutor.QUERY_DESCRIBE_ONLY
        | QueryExecutor.QUERY_SUPPRESS_BEGIN;
    StatementResultHandler handler = new StatementResultHandler();
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
    // Single query cannot have more than {@link Short#MAX_VALUE} binds, thus
    // the number of multi-values blocks should be capped.
    // Typically, it does not make much sense to batch more than 128 rows: performance
    // does not improve much after updating 128 statements with 1 multi-valued one, thus
    // we cap maximum batch size and split there.
    final int bindCount = originalQuery.getBindCount();
    final int highestBlockCount = 128;
    final int maxValueBlocks = bindCount == 0 ? 1024 /* if no binds, use 1024 rows */
        : Integer.highestOneBit( // deriveForMultiBatch supports powers of two only
            Math.min(Math.max(1, (Short.MAX_VALUE - 1) / bindCount), highestBlockCount));
    int unprocessedBatchCount = batchParameters.size();
    final int fullValueBlocksCount = unprocessedBatchCount / maxValueBlocks;
    final int partialValueBlocksCount = Integer.bitCount(unprocessedBatchCount % maxValueBlocks);
    final int count = fullValueBlocksCount + partialValueBlocksCount;
    ArrayList<Query> newBatchStatements = new ArrayList<Query>(count);
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
