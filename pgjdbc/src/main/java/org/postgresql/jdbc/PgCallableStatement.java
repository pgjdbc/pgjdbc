/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.Driver;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.exception.PgSqlState;
import org.postgresql.util.GT;

import org.checkerframework.checker.index.qual.Positive;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;
import java.util.Map;

class PgCallableStatement extends PgPreparedStatement implements CallableStatement {
  // Used by the callablestatement style methods
  private final boolean isFunction;
  // functionReturnType contains the user supplied value to check
  // testReturn contains a modified version to make it easier to
  // check the getXXX methods..
  private int @Nullable [] functionReturnType;
  private int @Nullable [] testReturn;
  // returnTypeSet is true when a proper call to registerOutParameter has been made
  private boolean returnTypeSet;
  protected @Nullable Object @Nullable [] callResult;
  private int lastIndex = 0;

  PgCallableStatement(PgConnection connection, String sql, int rsType, int rsConcurrency,
      int rsHoldability) throws SQLException {
    super(connection, connection.borrowCallableQuery(sql), rsType, rsConcurrency, rsHoldability);
    this.isFunction = preparedQuery.isFunction;

    if (this.isFunction) {
      int inParamCount = this.preparedParameters.getInParameterCount() + 1;
      this.testReturn = new int[inParamCount];
      this.functionReturnType = new int[inParamCount];
    }
  }

  @Override
  public int executeUpdate() throws SQLException {
    if (isFunction) {
      executeWithFlags(0);
      return 0;
    }
    return super.executeUpdate();
  }

  @Override
  public @Nullable Object getObject(@Positive int i, @Nullable Map<String, Class<?>> map)
      throws SQLException {
    return getObjectImpl(i, map);
  }

  @Override
  public @Nullable Object getObject(String s, @Nullable Map<String, Class<?>> map) throws SQLException {
    return getObjectImpl(s, map);
  }

  @Override
  public boolean executeWithFlags(int flags) throws SQLException {
    boolean hasResultSet = super.executeWithFlags(flags);
    int[] functionReturnType = this.functionReturnType;
    if (!isFunction || !returnTypeSet || functionReturnType == null) {
      return hasResultSet;
    }

    // If we are executing and there are out parameters
    // callable statement function set the return data
    if (!hasResultSet) {
      throw new SQLException(GT.tr("A CallableStatement was executed with nothing returned."),
          PgSqlState.NO_DATA);
    }

    ResultSet rs = castNonNull(getResultSet());
    if (!rs.next()) {
      throw new SQLException(GT.tr("A CallableStatement was executed with nothing returned."),
          PgSqlState.NO_DATA);
    }

    // figure out how many columns
    int cols = rs.getMetaData().getColumnCount();

    int outParameterCount = preparedParameters.getOutParameterCount();

    if (cols != outParameterCount) {
      throw new SQLSyntaxErrorException(
          GT.tr("A CallableStatement was executed with an invalid number of parameters"),
          PgSqlState.SYNTAX_ERROR);
    }

    // reset last result fetched (for wasNull)
    lastIndex = 0;

    // allocate enough space for all possible parameters without regard to in/out
    @Nullable Object[] callResult = new Object[preparedParameters.getParameterCount() + 1];
    this.callResult = callResult;

    // move them into the result set
    for (int i = 0, j = 0; i < cols; i++, j++) {
      // find the next out parameter, the assumption is that the functionReturnType
      // array will be initialized with 0 and only out parameters will have values
      // other than 0. 0 is the value for java.sql.Types.NULL, which should not
      // conflict
      while (j < functionReturnType.length && functionReturnType[j] == 0) {
        j++;
      }

      callResult[j] = rs.getObject(i + 1);
      int columnType = rs.getMetaData().getColumnType(i + 1);

      if (columnType != functionReturnType[j]) {
        // this is here for the sole purpose of passing the cts
        if (columnType == Types.DOUBLE && functionReturnType[j] == Types.REAL) {
          // return it as a float
          Object result = callResult[j];
          if (result != null) {
            callResult[j] = ((Double) result).floatValue();
          }
        } else if (columnType == Types.REF_CURSOR && functionReturnType[j] == Types.OTHER) {
          // For backwards compatibility reasons we support that ref cursors can be
          // registered with both Types.OTHER and Types.REF_CURSOR so we allow
          // this specific mismatch
        } else {
          throw new SQLSyntaxErrorException(GT.tr(
              "A CallableStatement function was executed and the out parameter {0} was of type {1} however type {2} was registered.",
              i + 1, "java.sql.Types=" + columnType, "java.sql.Types=" + functionReturnType[j]),
              PgSqlState.DATATYPE_MISMATCH);
        }
      }

    }
    rs.close();
    synchronized (this) {
      result = null;
    }
    return false;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Before executing a stored procedure call you must explicitly call registerOutParameter to
   * register the java.sql.Type of each out parameter.</p>
   *
   * <p>Note: When reading the value of an out parameter, you must use the getXXX method whose Java
   * type XXX corresponds to the parameter's registered SQL type.</p>
   *
   * <p>ONLY 1 RETURN PARAMETER if {?= call ..} syntax is used</p>
   *
   * @param parameterIndex the first parameter is 1, the second is 2,...
   * @param sqlType SQL type code defined by java.sql.Types; for parameters of type Numeric or
   *        Decimal use the version of registerOutParameter that accepts a scale value
   * @throws SQLException if a database-access error occurs.
   */
  @Override
  public void registerOutParameter(@Positive int parameterIndex, int sqlType)
      throws SQLException {
    checkClosed();
    switch (sqlType) {
      case Types.TINYINT:
        // we don't have a TINYINT type use SMALLINT
        sqlType = Types.SMALLINT;
        break;
      case Types.LONGVARCHAR:
        sqlType = Types.VARCHAR;
        break;
      case Types.DECIMAL:
        sqlType = Types.NUMERIC;
        break;
      case Types.FLOAT:
        // float is the same as double
        sqlType = Types.DOUBLE;
        break;
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
        sqlType = Types.BINARY;
        break;
      case Types.BOOLEAN:
        sqlType = Types.BIT;
        break;
      default:
        break;
    }
    int[] functionReturnType = this.functionReturnType;
    int[] testReturn = this.testReturn;
    if (!isFunction || functionReturnType == null || testReturn == null) {
      throw new SQLSyntaxErrorException(
          GT.tr(
              "This statement does not declare an OUT parameter.  Use '{' ?= call ... '}' to declare one."),
          PgSqlState.INVALID_FUNCTION_DEFINITION);
    }

    preparedParameters.registerOutParameter(parameterIndex, sqlType);
    // functionReturnType contains the user supplied value to check
    // testReturn contains a modified version to make it easier to
    // check the getXXX methods..
    functionReturnType[parameterIndex - 1] = sqlType;
    testReturn[parameterIndex - 1] = sqlType;

    if (functionReturnType[parameterIndex - 1] == Types.CHAR
        || functionReturnType[parameterIndex - 1] == Types.LONGVARCHAR) {
      testReturn[parameterIndex - 1] = Types.VARCHAR;
    } else if (functionReturnType[parameterIndex - 1] == Types.FLOAT) {
      testReturn[parameterIndex - 1] = Types.REAL; // changes to streamline later error checking
    }
    returnTypeSet = true;
  }

  @Override
  public boolean wasNull() throws SQLException {
    if (lastIndex == 0 || callResult == null) {
      throw new SQLException(GT.tr("wasNull cannot be call before fetching a result."),
          PgSqlState.OBJECT_NOT_IN_PREREQUISITE_STATE);
    }

    // check to see if the last access threw an exception
    return callResult[lastIndex - 1] == null;
  }

  @Override
  public @Nullable String getString(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.VARCHAR, "String");
    return (String) result;
  }

  @Override
  public boolean getBoolean(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.BIT, "Boolean");
    if (result == null) {
      return false;
    }

    return (Boolean) result;
  }

  @Override
  public byte getByte(@Positive int parameterIndex) throws SQLException {
    // fake tiny int with smallint
    Object result = checkIndex(parameterIndex, Types.SMALLINT, "Byte");

    if (result == null) {
      return 0;
    }

    return ((Integer) result).byteValue();

  }

  @Override
  public short getShort(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.SMALLINT, "Short");
    if (result == null) {
      return 0;
    }
    return ((Integer) result).shortValue();
  }

  @Override
  public int getInt(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.INTEGER, "Int");
    if (result == null) {
      return 0;
    }

    return (Integer) result;
  }

  @Override
  public long getLong(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.BIGINT, "Long");
    if (result == null) {
      return 0;
    }

    return (Long) result;
  }

  @Override
  public float getFloat(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.REAL, "Float");
    if (result == null) {
      return 0;
    }

    return (Float) result;
  }

  @Override
  public double getDouble(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.DOUBLE, "Double");
    if (result == null) {
      return 0;
    }

    return (Double) result;
  }

  @Override
  public @Nullable BigDecimal getBigDecimal(@Positive int parameterIndex, int scale) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.NUMERIC, "BigDecimal");
    return (@Nullable BigDecimal) result;
  }

  @Override
  public byte @Nullable [] getBytes(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.VARBINARY, Types.BINARY, "Bytes");
    return ((byte @Nullable []) result);
  }

  @Override
  public java.sql.@Nullable Date getDate(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.DATE, "Date");
    return (java.sql.@Nullable Date) result;
  }

  @Override
  public java.sql.@Nullable Time getTime(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.TIME, "Time");
    return (java.sql.@Nullable Time) result;
  }

  @Override
  public java.sql.@Nullable Timestamp getTimestamp(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.TIMESTAMP, "Timestamp");
    return (java.sql.@Nullable Timestamp) result;
  }

  @Override
  public @Nullable Object getObject(@Positive int parameterIndex) throws SQLException {
    return getCallResult(parameterIndex);
  }

  /**
   * helperfunction for the getXXX calls to check isFunction and index == 1 Compare BOTH type fields
   * against the return type.
   *
   * @param parameterIndex parameter index (1-based)
   * @param type1 type 1
   * @param type2 type 2
   * @param getName getter name
   * @throws SQLException if something goes wrong
   */
  protected @Nullable Object checkIndex(@Positive int parameterIndex, int type1, int type2, String getName)
      throws SQLException {
    Object result = getCallResult(parameterIndex);
    int testReturn = this.testReturn != null ? this.testReturn[parameterIndex - 1] : -1;
    if (type1 != testReturn && type2 != testReturn) {
      throw new SQLDataException(
          GT.tr("Parameter of type {0} was registered, but call to get{1} (sqltype={2}) was made.",
                  "java.sql.Types=" + testReturn, getName,
                  "java.sql.Types=" + type1),
          PgSqlState.MOST_SPECIFIC_TYPE_MISMATCH);
    }
    return result;
  }

  /**
   * Helper function for the getXXX calls to check isFunction and index == 1.
   *
   * @param parameterIndex parameter index (1-based)
   * @param type type
   * @param getName getter name
   * @throws SQLException if given index is not valid
   */
  protected @Nullable Object checkIndex(@Positive int parameterIndex,
      int type, String getName) throws SQLException {
    Object result = getCallResult(parameterIndex);
    int testReturn = this.testReturn != null ? this.testReturn[parameterIndex - 1] : -1;
    if (type != testReturn) {
      throw new SQLDataException(
          GT.tr("Parameter of type {0} was registered, but call to get{1} (sqltype={2}) was made.",
              "java.sql.Types=" + testReturn, getName,
                  "java.sql.Types=" + type),
          PgSqlState.MOST_SPECIFIC_TYPE_MISMATCH);
    }
    return result;
  }

  private @Nullable Object getCallResult(@Positive int parameterIndex) throws SQLException {
    checkClosed();

    if (!isFunction) {
      throw new SQLException(
          GT.tr(
              "A CallableStatement was declared, but no call to registerOutParameter(1, <some type>) was made."),
          PgSqlState.OBJECT_NOT_IN_PREREQUISITE_STATE);
    }

    if (!returnTypeSet) {
      throw new SQLException(GT.tr("No function outputs were registered."),
          PgSqlState.OBJECT_NOT_IN_PREREQUISITE_STATE);
    }

    @Nullable Object @Nullable [] callResult = this.callResult;
    if (callResult == null) {
      throw new SQLException(
          GT.tr("Results cannot be retrieved from a CallableStatement before it is executed."),
          PgSqlState.OBJECT_NOT_IN_PREREQUISITE_STATE);
    }

    lastIndex = parameterIndex;
    return callResult[parameterIndex - 1];
  }

  @Override
  protected BatchResultHandler createBatchHandler(Query[] queries,
      @Nullable ParameterList[] parameterLists) {
    return new CallableBatchResultHandler(this, queries, parameterLists);
  }

  @Override
  public java.sql.@Nullable Array getArray(int i) throws SQLException {
    Object result = checkIndex(i, Types.ARRAY, "Array");
    return (Array) result;
  }

  @Override
  public java.math.@Nullable BigDecimal getBigDecimal(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.NUMERIC, "BigDecimal");
    return ((BigDecimal) result);
  }

  @Override
  public @Nullable Blob getBlob(int i) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getBlob(int)");
  }

  @Override
  public @Nullable Clob getClob(int i) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getClob(int)");
  }

  public @Nullable Object getObjectImpl(int i, @Nullable Map<String, Class<?>> map) throws SQLException {
    if (map == null || map.isEmpty()) {
      return getObject(i);
    }
    throw Driver.notImplemented(this.getClass(), "getObjectImpl(int,Map)");
  }

  @Override
  public @Nullable Ref getRef(int i) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getRef(int)");
  }

  @Override
  public java.sql.@Nullable Date getDate(int i, java.util.@Nullable Calendar cal) throws SQLException {
    Object result = checkIndex(i, Types.DATE, "Date");

    if (result == null) {
      return null;
    }

    String value = result.toString();
    return connection.getTimestampUtils().toDate(cal, value);
  }

  @Override
  public @Nullable Time getTime(int i, java.util.@Nullable Calendar cal) throws SQLException {
    Object result = checkIndex(i, Types.TIME, "Time");

    if (result == null) {
      return null;
    }

    String value = result.toString();
    return connection.getTimestampUtils().toTime(cal, value);
  }

  @Override
  public @Nullable Timestamp getTimestamp(int i, java.util.@Nullable Calendar cal) throws SQLException {
    Object result = checkIndex(i, Types.TIMESTAMP, "Timestamp");

    if (result == null) {
      return null;
    }

    String value = result.toString();
    return connection.getTimestampUtils().toTimestamp(cal, value);
  }

  @Override
  public void registerOutParameter(@Positive int parameterIndex, int sqlType, String typeName)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter(int,int,String)");
  }

  @Override
  public void setObject(String parameterName, @Nullable Object x, java.sql.SQLType targetSqlType,
      int scaleOrLength) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject");
  }

  @Override
  public void setObject(String parameterName, @Nullable Object x, java.sql.SQLType targetSqlType)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject");
  }

  @Override
  public void registerOutParameter(@Positive int parameterIndex, java.sql.SQLType sqlType)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }

  @Override
  public void registerOutParameter(@Positive int parameterIndex, java.sql.SQLType sqlType, int scale)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }

  @Override
  public void registerOutParameter(@Positive int parameterIndex, java.sql.SQLType sqlType, String typeName)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }

  @Override
  public void registerOutParameter(String parameterName, java.sql.SQLType sqlType)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }

  @Override
  public void registerOutParameter(String parameterName, java.sql.SQLType sqlType, int scale)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }

  @Override
  public void registerOutParameter(String parameterName, java.sql.SQLType sqlType, String typeName)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }

  @Override
  public @Nullable RowId getRowId(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getRowId(int)");
  }

  @Override
  public @Nullable RowId getRowId(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getRowId(String)");
  }

  @Override
  public void setRowId(String parameterName, @Nullable RowId x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setRowId(String, RowId)");
  }

  @Override
  public void setNString(String parameterName, @Nullable String value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNString(String, String)");
  }

  @Override
  public void setNCharacterStream(String parameterName, @Nullable Reader value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNCharacterStream(String, Reader, long)");
  }

  @Override
  public void setNCharacterStream(String parameterName, @Nullable Reader value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNCharacterStream(String, Reader)");
  }

  @Override
  public void setCharacterStream(String parameterName, @Nullable Reader value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setCharacterStream(String, Reader, long)");
  }

  @Override
  public void setCharacterStream(String parameterName, @Nullable Reader value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setCharacterStream(String, Reader)");
  }

  @Override
  public void setBinaryStream(String parameterName, @Nullable InputStream value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBinaryStream(String, InputStream, long)");
  }

  @Override
  public void setBinaryStream(String parameterName, @Nullable InputStream value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBinaryStream(String, InputStream)");
  }

  @Override
  public void setAsciiStream(String parameterName, @Nullable InputStream value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setAsciiStream(String, InputStream, long)");
  }

  @Override
  public void setAsciiStream(String parameterName, @Nullable InputStream value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setAsciiStream(String, InputStream)");
  }

  @Override
  public void setNClob(String parameterName, @Nullable NClob value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(String, NClob)");
  }

  @Override
  public void setClob(String parameterName, @Nullable Reader reader, long length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setClob(String, Reader, long)");
  }

  @Override
  public void setClob(String parameterName, @Nullable Reader reader) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setClob(String, Reader)");
  }

  @Override
  public void setBlob(String parameterName, @Nullable InputStream inputStream, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBlob(String, InputStream, long)");
  }

  @Override
  public void setBlob(String parameterName, @Nullable InputStream inputStream) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBlob(String, InputStream)");
  }

  @Override
  public void setBlob(String parameterName, @Nullable Blob x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBlob(String, Blob)");
  }

  @Override
  public void setClob(String parameterName, @Nullable Clob x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setClob(String, Clob)");
  }

  @Override
  public void setNClob(String parameterName, @Nullable Reader reader, long length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(String, Reader, long)");
  }

  @Override
  public void setNClob(String parameterName, @Nullable Reader reader) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(String, Reader)");
  }

  @Override
  public @Nullable NClob getNClob(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNClob(int)");
  }

  @Override
  public @Nullable NClob getNClob(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNClob(String)");
  }

  @Override
  public void setSQLXML(String parameterName, @Nullable SQLXML xmlObject) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setSQLXML(String, SQLXML)");
  }

  @Override
  public @Nullable SQLXML getSQLXML(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.SQLXML, "SQLXML");
    return (SQLXML) result;
  }

  @Override
  public @Nullable SQLXML getSQLXML(String parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getSQLXML(String)");
  }

  @Override
  public String getNString(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNString(int)");
  }

  @Override
  public @Nullable String getNString(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNString(String)");
  }

  @Override
  public @Nullable Reader getNCharacterStream(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNCharacterStream(int)");
  }

  @Override
  public @Nullable Reader getNCharacterStream(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNCharacterStream(String)");
  }

  @Override
  public @Nullable Reader getCharacterStream(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getCharacterStream(int)");
  }

  @Override
  public @Nullable Reader getCharacterStream(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getCharacterStream(String)");
  }

  @Override
  public <T> @Nullable T getObject(@Positive int parameterIndex, Class<T> type)
      throws SQLException {
    if (type == ResultSet.class) {
      return type.cast(getObject(parameterIndex));
    }
    throw new SQLDataException(GT.tr("Unsupported type conversion to {1}.", type),
        PgSqlState.INVALID_PARAMETER_VALUE);
  }

  @Override
  public <T> @Nullable T getObject(String parameterName, Class<T> type) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getObject(String, Class<T>)");
  }

  @Override
  public void registerOutParameter(String parameterName, int sqlType) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter(String,int)");
  }

  @Override
  public void registerOutParameter(String parameterName, int sqlType, int scale)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter(String,int,int)");
  }

  @Override
  public void registerOutParameter(String parameterName, int sqlType, String typeName)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter(String,int,String)");
  }

  @Override
  public java.net.@Nullable URL getURL(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getURL(String)");
  }

  @Override
  public void setURL(String parameterName, java.net.@Nullable URL val) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setURL(String,URL)");
  }

  @Override
  public void setNull(String parameterName, int sqlType) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNull(String,int)");
  }

  @Override
  public void setBoolean(String parameterName, boolean x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBoolean(String,boolean)");
  }

  @Override
  public void setByte(String parameterName, byte x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setByte(String,byte)");
  }

  @Override
  public void setShort(String parameterName, short x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setShort(String,short)");
  }

  @Override
  public void setInt(String parameterName, int x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setInt(String,int)");
  }

  @Override
  public void setLong(String parameterName, long x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setLong(String,long)");
  }

  @Override
  public void setFloat(String parameterName, float x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setFloat(String,float)");
  }

  @Override
  public void setDouble(String parameterName, double x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setDouble(String,double)");
  }

  @Override
  public void setBigDecimal(String parameterName, @Nullable BigDecimal x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBigDecimal(String,BigDecimal)");
  }

  @Override
  public void setString(String parameterName, @Nullable String x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setString(String,String)");
  }

  @Override
  public void setBytes(String parameterName, byte @Nullable [] x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBytes(String,byte)");
  }

  @Override
  public void setDate(String parameterName, java.sql.@Nullable Date x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setDate(String,Date)");
  }

  @Override
  public void setTime(String parameterName, @Nullable Time x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setTime(String,Time)");
  }

  @Override
  public void setTimestamp(String parameterName, @Nullable Timestamp x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setTimestamp(String,Timestamp)");
  }

  @Override
  public void setAsciiStream(String parameterName, @Nullable InputStream x, int length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setAsciiStream(String,InputStream,int)");
  }

  @Override
  public void setBinaryStream(String parameterName, @Nullable InputStream x, int length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBinaryStream(String,InputStream,int)");
  }

  @Override
  public void setObject(String parameterName, @Nullable Object x, int targetSqlType, int scale)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject(String,Object,int,int)");
  }

  @Override
  public void setObject(String parameterName, @Nullable Object x, int targetSqlType) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject(String,Object,int)");
  }

  @Override
  public void setObject(String parameterName, @Nullable Object x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject(String,Object)");
  }

  @Override
  public void setCharacterStream(String parameterName, @Nullable Reader reader, int length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setCharacterStream(String,Reader,int)");
  }

  @Override
  public void setDate(String parameterName, java.sql.@Nullable Date x, @Nullable Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setDate(String,Date,Calendar)");
  }

  @Override
  public void setTime(String parameterName, @Nullable Time x, @Nullable Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setTime(String,Time,Calendar)");
  }

  @Override
  public void setTimestamp(String parameterName, @Nullable Timestamp x, @Nullable Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setTimestamp(String,Timestamp,Calendar)");
  }

  @Override
  public void setNull(String parameterName, int sqlType, String typeName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNull(String,int,String)");
  }

  @Override
  public @Nullable String getString(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getString(String)");
  }

  @Override
  public boolean getBoolean(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getBoolean(String)");
  }

  @Override
  public byte getByte(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getByte(String)");
  }

  @Override
  public short getShort(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getShort(String)");
  }

  @Override
  public int getInt(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getInt(String)");
  }

  @Override
  public long getLong(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getLong(String)");
  }

  @Override
  public float getFloat(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getFloat(String)");
  }

  @Override
  public double getDouble(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getDouble(String)");
  }

  @Override
  public byte @Nullable [] getBytes(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getBytes(String)");
  }

  @Override
  public java.sql.@Nullable Date getDate(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getDate(String)");
  }

  @Override
  public Time getTime(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getTime(String)");
  }

  @Override
  public @Nullable Timestamp getTimestamp(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getTimestamp(String)");
  }

  @Override
  public @Nullable Object getObject(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getObject(String)");
  }

  @Override
  public @Nullable BigDecimal getBigDecimal(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getBigDecimal(String)");
  }

  public @Nullable Object getObjectImpl(String parameterName, @Nullable Map<String, Class<?>> map) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getObject(String,Map)");
  }

  @Override
  public @Nullable Ref getRef(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getRef(String)");
  }

  @Override
  public @Nullable Blob getBlob(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getBlob(String)");
  }

  @Override
  public @Nullable Clob getClob(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getClob(String)");
  }

  @Override
  public @Nullable Array getArray(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getArray(String)");
  }

  @Override
  public java.sql.@Nullable Date getDate(String parameterName, @Nullable Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getDate(String,Calendar)");
  }

  @Override
  public @Nullable Time getTime(String parameterName, @Nullable Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getTime(String,Calendar)");
  }

  @Override
  public @Nullable Timestamp getTimestamp(String parameterName, @Nullable Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getTimestamp(String,Calendar)");
  }

  @Override
  public java.net.@Nullable URL getURL(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getURL(String)");
  }

  @Override
  public void registerOutParameter(@Positive int parameterIndex, int sqlType, int scale) throws SQLException {
    // ignore scale for now
    registerOutParameter(parameterIndex, sqlType);
  }
}
