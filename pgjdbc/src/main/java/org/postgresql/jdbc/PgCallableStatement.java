/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.Driver;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.util.GT;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
//#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
//#endif
import java.util.Calendar;
import java.util.Map;
import java.util.UUID;

class PgCallableStatement extends PgPreparedStatement implements CallableStatement {
  // Used by the callablestatement style methods
  private boolean isFunction;
  // functionReturnType contains the user supplied value to check
  // testReturn contains a modified version to make it easier to
  // check the getXXX methods..
  private int[] functionReturnType;
  private int[] testReturn;
  // returnTypeSet is true when a proper call to registerOutParameter has been made
  private boolean returnTypeSet;

  /*
   * Contains the values of calling #getObject(int) on the result set.
   * For temporal types when running in JDBC 4.2 or later contains java.time
   * types instead of java.sql types. The former can be converted without loss
   * to the later but not the other way around.
   * Checks have to be made in #getDate #getTime and #getTimestamp
   */
  protected Object[] callResult;
  private int lastIndex = 0;

  PgCallableStatement(PgConnection connection, String sql, int rsType, int rsConcurrency,
      int rsHoldability) throws SQLException {
    super(connection, connection.borrowCallableQuery(sql), rsType, rsConcurrency, rsHoldability);
    this.isFunction = preparedQuery.isFunction;
    this.outParmBeforeFunc = preparedQuery.outParmBeforeFunc;

    if (this.isFunction) {
      int inParamCount = this.preparedParameters.getInParameterCount() + 1;
      this.testReturn = new int[inParamCount];
      this.functionReturnType = new int[inParamCount];
    }
  }

  public int executeUpdate() throws SQLException {
    if (isFunction) {
      executeWithFlags(0);
      return 0;
    }
    return super.executeUpdate();
  }

  public Object getObject(int i, Map<String, Class<?>> map) throws SQLException {
    return getObjectImpl(i, map);
  }

  public Object getObject(String s, Map<String, Class<?>> map) throws SQLException {
    return getObjectImpl(s, map);
  }

  @Override
  public boolean executeWithFlags(int flags) throws SQLException {
    boolean hasResultSet = super.executeWithFlags(flags);
    if (!isFunction || !returnTypeSet) {
      return hasResultSet;
    }

    // If we are executing and there are out parameters
    // callable statement function set the return data
    if (!hasResultSet) {
      throw new PSQLException(GT.tr("A CallableStatement was executed with nothing returned."),
          PSQLState.NO_DATA);
    }

    ResultSet rs = result.getResultSet();
    if (!rs.next()) {
      throw new PSQLException(GT.tr("A CallableStatement was executed with nothing returned."),
          PSQLState.NO_DATA);
    }

    // figure out how many columns
    int cols = rs.getMetaData().getColumnCount();

    int outParameterCount = preparedParameters.getOutParameterCount();

    if (cols != outParameterCount) {
      throw new PSQLException(
          GT.tr("A CallableStatement was executed with an invalid number of parameters"),
          PSQLState.SYNTAX_ERROR);
    }

    // reset last result fetched (for wasNull)
    lastIndex = 0;

    // allocate enough space for all possible parameters without regard to in/out
    callResult = new Object[preparedParameters.getParameterCount() + 1];

    // move them into the result set
    for (int i = 0, j = 0; i < cols; i++, j++) {
      // find the next out parameter, the assumption is that the functionReturnType
      // array will be initialized with 0 and only out parameters will have values
      // other than 0. 0 is the value for java.sql.Types.NULL, which should not
      // conflict
      while (j < functionReturnType.length && functionReturnType[j] == 0) {
        j++;
      }

      //#if mvn.project.property.postgresql.jdbc.spec <= "JDBC4.1"
      callResult[j] = rs.getObject(i + 1);
      //#endif
      //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
      switch (functionReturnType[j]) {
        case Types.TIMESTAMP_WITH_TIMEZONE:
          callResult[j] = rs.getObject(i + 1, OffsetDateTime.class);
          break;
        case Types.TIMESTAMP:
          callResult[j] = rs.getObject(i + 1, LocalDateTime.class);
          break;
        case Types.DATE:
          callResult[j] = rs.getObject(i + 1, LocalDate.class);
          break;
        case Types.TIME:
          callResult[j] = rs.getObject(i + 1, LocalTime.class);
          break;
        default:
          callResult[j] = rs.getObject(i + 1);
          break;
      }
      //#endif



      int columnType = rs.getMetaData().getColumnType(i + 1);

      if (columnType != functionReturnType[j]) {
        // this is here for the sole purpose of passing the cts
        if (columnType == Types.DOUBLE && functionReturnType[j] == Types.REAL) {
          // return it as a float
          if (callResult[j] != null) {
            callResult[j] = ((Double) callResult[j]).floatValue();
          }
          //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
        } else if (columnType == Types.REF_CURSOR && functionReturnType[j] == Types.OTHER) {
          // For backwards compatibility reasons we support that ref cursors can be
          // registered with both Types.OTHER and Types.REF_CURSOR so we allow
          // this specific mismatch
          //#endif
        } else {
          throw new PSQLException(GT.tr(
              "A CallableStatement function was executed and the out parameter {0} was of type {1} however type {2} was registered.",
              i + 1, "java.sql.Types=" + columnType, "java.sql.Types=" + functionReturnType[j]),
              PSQLState.DATA_TYPE_MISMATCH);
        }
      }

    }
    rs.close();
    result = null;
    return false;
  }

  /**
   * Before executing a stored procedure call you must explicitly call registerOutParameter to
   * register the java.sql.Type of each out parameter.
   *
   * <p>
   * Note: When reading the value of an out parameter, you must use the getXXX method whose Java
   * type XXX corresponds to the parameter's registered SQL type.
   *
   * ONLY 1 RETURN PARAMETER if {?= call ..} syntax is used
   *
   * @param parameterIndex the first parameter is 1, the second is 2,...
   * @param sqlType SQL type code defined by java.sql.Types; for parameters of type Numeric or
   *        Decimal use the version of registerOutParameter that accepts a scale value
   * @throws SQLException if a database-access error occurs.
   */
  public void registerOutParameter(int parameterIndex, int sqlType, boolean setPreparedParameters)
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
      default:
        break;
    }
    if (!isFunction) {
      throw new PSQLException(
          GT.tr(
              "This statement does not declare an OUT parameter.  Use '{' ?= call ... '}' to declare one."),
          PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL);
    }
    checkIndex(parameterIndex, false);

    if (setPreparedParameters) {
      preparedParameters.registerOutParameter(parameterIndex, sqlType);
    }
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

  /**
   * You must also specify the scale for numeric/decimal types:
   *
   * <p>
   * Note: When reading the value of an out parameter, you must use the getXXX method whose Java
   * type XXX corresponds to the parameter's registered SQL type.
   *
   * @param parameterIndex the first parameter is 1, the second is 2,...
   * @param sqlType use either java.sql.Type.NUMERIC or java.sql.Type.DECIMAL
   * @param scale a value greater than or equal to zero representing the desired number of digits to
   *        the right of the decimal point
   * @param setPreparedParameters set prepared parameters
   * @throws SQLException if a database-access error occurs.
   */
  public void registerOutParameter(int parameterIndex, int sqlType, int scale,
      boolean setPreparedParameters) throws SQLException {
    registerOutParameter(parameterIndex, sqlType, setPreparedParameters); // ignore for now..
  }

  public boolean wasNull() throws SQLException {
    if (lastIndex == 0) {
      throw new PSQLException(GT.tr("wasNull cannot be call before fetching a result."),
          PSQLState.OBJECT_NOT_IN_STATE);
    }

    // check to see if the last access threw an exception
    return (callResult[lastIndex - 1] == null);
  }

  public String getString(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.VARCHAR, "String");
    return (String) callResult[parameterIndex - 1];
  }

  public boolean getBoolean(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.BIT, "Boolean");
    if (callResult[parameterIndex - 1] == null) {
      return false;
    }

    return (Boolean) callResult[parameterIndex - 1];
  }

  public byte getByte(int parameterIndex) throws SQLException {
    checkClosed();
    // fake tiny int with smallint
    checkIndex(parameterIndex, Types.SMALLINT, "Byte");

    if (callResult[parameterIndex - 1] == null) {
      return 0;
    }

    return ((Integer) callResult[parameterIndex - 1]).byteValue();

  }

  public short getShort(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.SMALLINT, "Short");
    if (callResult[parameterIndex - 1] == null) {
      return 0;
    }
    return ((Integer) callResult[parameterIndex - 1]).shortValue();
  }

  public int getInt(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.INTEGER, "Int");
    if (callResult[parameterIndex - 1] == null) {
      return 0;
    }

    return (Integer) callResult[parameterIndex - 1];
  }

  public long getLong(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.BIGINT, "Long");
    if (callResult[parameterIndex - 1] == null) {
      return 0;
    }

    return (Long) callResult[parameterIndex - 1];
  }

  public float getFloat(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.REAL, "Float");
    if (callResult[parameterIndex - 1] == null) {
      return 0;
    }

    return (Float) callResult[parameterIndex - 1];
  }

  public double getDouble(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.DOUBLE, "Double");
    if (callResult[parameterIndex - 1] == null) {
      return 0;
    }

    return (Double) callResult[parameterIndex - 1];
  }

  public BigDecimal getBigDecimal(int parameterIndex, int scale) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.NUMERIC, "BigDecimal");
    return ((BigDecimal) callResult[parameterIndex - 1]);
  }

  public byte[] getBytes(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.VARBINARY, Types.BINARY, "Bytes");
    return ((byte[]) callResult[parameterIndex - 1]);
  }

  public java.sql.Date getDate(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.DATE, "Date");
    Object value = callResult[parameterIndex - 1];
    java.sql.Date date = null;
    //#if mvn.project.property.postgresql.jdbc.spec <= "JDBC4.1"
    if (value instanceof java.sql.Date) {
      date = (java.sql.Date) value;
    }
    //#endif
    //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
    date = java.sql.Date.valueOf((LocalDate) value);
    //#endif
    return date;
  }

  public java.sql.Time getTime(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.TIME, "Time");
    Object value = callResult[parameterIndex - 1];
    java.sql.Time time = null;
    //#if mvn.project.property.postgresql.jdbc.spec <= "JDBC4.1"
    if (value instanceof java.sql.Time) {
      time = (java.sql.Time) value;
    }
    //#endif
    //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
    time =  java.sql.Time.valueOf((LocalTime) value);
    //#endif
    return time;
  }

  public java.sql.Timestamp getTimestamp(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.TIMESTAMP, "Timestamp");
    Object value = callResult[parameterIndex - 1];
    java.sql.Timestamp timestamp = null;
    //#if mvn.project.property.postgresql.jdbc.spec <= "JDBC4.1"
    if (value instanceof java.sql.Timestamp) {
      timestamp = (java.sql.Timestamp) value;
    }
    //#endif
    //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
    timestamp =  java.sql.Timestamp.valueOf((LocalDateTime) value);
    //#endif
    return timestamp;
  }

  public Object getObject(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex);
    return callResult[parameterIndex - 1];
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  /**
   * helperfunction for the getXXX type calls to check isFunction and index == 1
   *
   * @param parameterIndex parameter index (1-based)
   * @param type type the SQL type
   * @param getName getter name
   * @throws SQLException if given index is not valid
   */
  private <T> T getJsr310Type(int parameterIndex, Class<T> javaType, int type, String getName) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, type, getName);
    return javaType.cast(callResult[parameterIndex - 1]);
  }
  //#endif

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
  protected void checkIndex(int parameterIndex, int type1, int type2, String getName)
      throws SQLException {
    checkIndex(parameterIndex);
    if (type1 != this.testReturn[parameterIndex - 1]
        && type2 != this.testReturn[parameterIndex - 1]) {
      throw new PSQLException(
          GT.tr("Parameter of type {0} was registered, but call to get{1} (sqltype={2}) was made.",
                  "java.sql.Types=" + testReturn[parameterIndex - 1], getName,
                  "java.sql.Types=" + type1),
          PSQLState.MOST_SPECIFIC_TYPE_DOES_NOT_MATCH);
    }
  }

  /**
   * helperfunction for the getXXX calls to check isFunction and index == 1
   *
   * @param parameterIndex parameter index (1-based)
   * @param type type
   * @param getName getter name
   * @throws SQLException if given index is not valid
   */
  protected void checkIndex(int parameterIndex, int type, String getName) throws SQLException {
    checkIndex(parameterIndex);
    if (type != this.testReturn[parameterIndex - 1]) {
      throw new PSQLException(
          GT.tr("Parameter of type {0} was registered, but call to get{1} (sqltype={2}) was made.",
              "java.sql.Types=" + testReturn[parameterIndex - 1], getName,
                  "java.sql.Types=" + type),
          PSQLState.MOST_SPECIFIC_TYPE_DOES_NOT_MATCH);
    }
  }

  private void checkIndex(int parameterIndex) throws SQLException {
    checkIndex(parameterIndex, true);
  }

  /**
   * helperfunction for the getXXX calls to check isFunction and index == 1
   *
   * @param parameterIndex index of getXXX (index) check to make sure is a function and index == 1
   * @param fetchingData fetching data
   */
  private void checkIndex(int parameterIndex, boolean fetchingData) throws SQLException {
    if (!isFunction) {
      throw new PSQLException(
          GT.tr(
              "A CallableStatement was declared, but no call to registerOutParameter(1, <some type>) was made."),
          PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL);
    }

    if (fetchingData) {
      if (!returnTypeSet) {
        throw new PSQLException(GT.tr("No function outputs were registered."),
            PSQLState.OBJECT_NOT_IN_STATE);
      }

      if (callResult == null) {
        throw new PSQLException(
            GT.tr("Results cannot be retrieved from a CallableStatement before it is executed."),
            PSQLState.NO_DATA);
      }

      lastIndex = parameterIndex;
    }
  }

  @Override
  protected BatchResultHandler createBatchHandler(Query[] queries,
      ParameterList[] parameterLists) {
    return new CallableBatchResultHandler(this, queries, parameterLists);
  }

  public java.sql.Array getArray(int i) throws SQLException {
    checkClosed();
    checkIndex(i, Types.ARRAY, "Array");
    return (Array) callResult[i - 1];
  }

  public java.math.BigDecimal getBigDecimal(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.NUMERIC, "BigDecimal");
    return ((BigDecimal) callResult[parameterIndex - 1]);
  }

  public Blob getBlob(int i) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getBlob(int)");
  }

  public Clob getClob(int i) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getClob(int)");
  }

  public Object getObjectImpl(int i, Map<String, Class<?>> map) throws SQLException {
    if (map == null || map.isEmpty()) {
      return getObject(i);
    }
    throw Driver.notImplemented(this.getClass(), "getObjectImpl(int,Map)");
  }

  public Ref getRef(int i) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getRef(int)");
  }

  public java.sql.Date getDate(int i, java.util.Calendar cal) throws SQLException {
    checkClosed();
    checkIndex(i, Types.DATE, "Date");

    if (callResult[i - 1] == null) {
      return null;
    }

    String value = callResult[i - 1].toString();
    return connection.getTimestampUtils().toDate(cal, value);
  }

  public Time getTime(int i, java.util.Calendar cal) throws SQLException {
    checkClosed();
    checkIndex(i, Types.TIME, "Time");

    if (callResult[i - 1] == null) {
      return null;
    }

    String value = callResult[i - 1].toString();
    return connection.getTimestampUtils().toTime(cal, value);
  }

  public Timestamp getTimestamp(int i, java.util.Calendar cal) throws SQLException {
    checkClosed();
    checkIndex(i, Types.TIMESTAMP, "Timestamp");

    if (callResult[i - 1] == null) {
      return null;
    }

    String value = null;
    //#if mvn.project.property.postgresql.jdbc.spec <= "JDBC4.2"
    value = callResult[i - 1].toString();
    //#endif
    //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
    value = Timestamp.valueOf((LocalDateTime) callResult[i - 1]).toString();
    //#endif
    return connection.getTimestampUtils().toTimestamp(cal, value);
  }

  public void registerOutParameter(int parameterIndex, int sqlType, String typeName)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter(int,int,String)");
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  public void setObject(String parameterName, Object x, java.sql.SQLType targetSqlType,
      int scaleOrLength) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject");
  }

  public void setObject(String parameterName, Object x, java.sql.SQLType targetSqlType)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject");
  }

  public void registerOutParameter(int parameterIndex, java.sql.SQLType sqlType)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }

  public void registerOutParameter(int parameterIndex, java.sql.SQLType sqlType, int scale)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }

  public void registerOutParameter(int parameterIndex, java.sql.SQLType sqlType, String typeName)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }

  public void registerOutParameter(String parameterName, java.sql.SQLType sqlType)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }

  public void registerOutParameter(String parameterName, java.sql.SQLType sqlType, int scale)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }

  public void registerOutParameter(String parameterName, java.sql.SQLType sqlType, String typeName)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter");
  }
  //#endif

  public RowId getRowId(int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getRowId(int)");
  }

  public RowId getRowId(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getRowId(String)");
  }

  public void setRowId(String parameterName, RowId x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setRowId(String, RowId)");
  }

  public void setNString(String parameterName, String value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNString(String, String)");
  }

  public void setNCharacterStream(String parameterName, Reader value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNCharacterStream(String, Reader, long)");
  }

  public void setNCharacterStream(String parameterName, Reader value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNCharacterStream(String, Reader)");
  }

  public void setCharacterStream(String parameterName, Reader value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setCharacterStream(String, Reader, long)");
  }

  public void setCharacterStream(String parameterName, Reader value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setCharacterStream(String, Reader)");
  }

  public void setBinaryStream(String parameterName, InputStream value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBinaryStream(String, InputStream, long)");
  }

  public void setBinaryStream(String parameterName, InputStream value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBinaryStream(String, InputStream)");
  }

  public void setAsciiStream(String parameterName, InputStream value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setAsciiStream(String, InputStream, long)");
  }

  public void setAsciiStream(String parameterName, InputStream value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setAsciiStream(String, InputStream)");
  }

  public void setNClob(String parameterName, NClob value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(String, NClob)");
  }

  public void setClob(String parameterName, Reader reader, long length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setClob(String, Reader, long)");
  }

  public void setClob(String parameterName, Reader reader) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setClob(String, Reader)");
  }

  public void setBlob(String parameterName, InputStream inputStream, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBlob(String, InputStream, long)");
  }

  public void setBlob(String parameterName, InputStream inputStream) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBlob(String, InputStream)");
  }

  public void setBlob(String parameterName, Blob x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBlob(String, Blob)");
  }

  public void setClob(String parameterName, Clob x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setClob(String, Clob)");
  }

  public void setNClob(String parameterName, Reader reader, long length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(String, Reader, long)");
  }

  public void setNClob(String parameterName, Reader reader) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(String, Reader)");
  }

  public NClob getNClob(int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNClob(int)");
  }

  public NClob getNClob(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNClob(String)");
  }

  public void setSQLXML(String parameterName, SQLXML xmlObject) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setSQLXML(String, SQLXML)");
  }

  public SQLXML getSQLXML(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.SQLXML, "SQLXML");
    return (SQLXML) callResult[parameterIndex - 1];
  }

  public SQLXML getSQLXML(String parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getSQLXML(String)");
  }

  public String getNString(int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNString(int)");
  }

  public String getNString(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNString(String)");
  }

  public Reader getNCharacterStream(int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNCharacterStream(int)");
  }

  public Reader getNCharacterStream(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNCharacterStream(String)");
  }

  public Reader getCharacterStream(int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getCharacterStream(int)");
  }

  public Reader getCharacterStream(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getCharacterStream(String)");
  }

  public <T> T getObject(int parameterIndex, Class<T> type) throws SQLException {
    if (type == null) {
      throw new SQLException("type is null");
    }
    checkIndex(parameterIndex);
    int sqlType = functionReturnType[parameterIndex - 1];
    if (type == BigDecimal.class) {
      if (sqlType == Types.NUMERIC || sqlType == Types.DECIMAL) {
        return type.cast(getBigDecimal(parameterIndex));
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
    } else if (type == String.class) {
      if (sqlType == Types.CHAR || sqlType == Types.VARCHAR) {
        return type.cast(getString(parameterIndex));
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
    } else if (type == Boolean.class) {
      if (sqlType == Types.BOOLEAN || sqlType == Types.BIT) {
        boolean booleanValue = getBoolean(parameterIndex);
        if (wasNull()) {
          return null;
        }
        return type.cast(booleanValue);
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
    } else if (type == Integer.class) {
      if (sqlType == Types.SMALLINT || sqlType == Types.INTEGER) {
        int intValue = getInt(parameterIndex);
        if (wasNull()) {
          return null;
        }
        return type.cast(intValue);
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
    } else if (type == Long.class) {
      if (sqlType == Types.BIGINT) {
        long longValue = getLong(parameterIndex);
        if (wasNull()) {
          return null;
        }
        return type.cast(longValue);
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
    } else if (type == Float.class) {
      if (sqlType == Types.REAL) {
        float floatValue = getFloat(parameterIndex);
        if (wasNull()) {
          return null;
        }
        return type.cast(floatValue);
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
    } else if (type == Double.class) {
      if (sqlType == Types.FLOAT || sqlType == Types.DOUBLE) {
        double doubleValue = getDouble(parameterIndex);
        if (wasNull()) {
          return null;
        }
        return type.cast(doubleValue);
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
    } else if (type == Date.class) {
      if (sqlType == Types.DATE) {
        return type.cast(getDate(parameterIndex));
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
    } else if (type == Time.class) {
      if (sqlType == Types.TIME) {
        return type.cast(getTime(parameterIndex));
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
    } else if (type == Timestamp.class) {
      if (sqlType == Types.TIMESTAMP
              //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
              || sqlType == Types.TIMESTAMP_WITH_TIMEZONE
      //#endif
      ) {
        return type.cast(getTimestamp(parameterIndex));
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
    } else if (type == Array.class) {
      if (sqlType == Types.ARRAY) {
        return type.cast(getArray(parameterIndex));
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
    } else if (type == SQLXML.class) {
      if (sqlType == Types.SQLXML) {
        return type.cast(getSQLXML(parameterIndex));
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
    } else if (type == UUID.class) {
      return type.cast(getObject(parameterIndex));
    } else if (type == InetAddress.class) {
      Object addressString = getObject(parameterIndex);
      if (addressString == null) {
        return null;
      }
      try {
        return type.cast(InetAddress.getByName(((PGobject) addressString).getValue()));
      } catch (UnknownHostException e) {
        throw new SQLException("could not create inet address from string '" + addressString + "'");
      }
    } else if (type == ResultSet.class) {
      return type.cast(getObject(parameterIndex));
      // JSR-310 support
      //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
    } else if (type == LocalDate.class) {
      if (sqlType == Types.DATE) {
        return getJsr310Type(parameterIndex, type, sqlType, "Date");
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
    } else if (type == LocalTime.class) {
      if (sqlType == Types.TIME) {
        return getJsr310Type(parameterIndex, type, sqlType, "Time");
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
    } else if (type == LocalDateTime.class) {
      if (sqlType == Types.TIMESTAMP) {
        return getJsr310Type(parameterIndex, type, sqlType, "Timestamp");
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
    } else if (type == OffsetDateTime.class) {
      if (sqlType == Types.TIMESTAMP_WITH_TIMEZONE) {
        return getJsr310Type(parameterIndex, type, sqlType, "Timestamp");
      } else {
        throw new SQLException("conversion to " + type + " from " + sqlType + " not supported");
      }
      //#endif
    }
    throw new SQLException("unsupported conversion to " + type);
  }

  public <T> T getObject(String parameterName, Class<T> type) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getObject(String, Class<T>)");
  }

  public void registerOutParameter(String parameterName, int sqlType) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter(String,int)");
  }

  public void registerOutParameter(String parameterName, int sqlType, int scale)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter(String,int,int)");
  }

  public void registerOutParameter(String parameterName, int sqlType, String typeName)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "registerOutParameter(String,int,String)");
  }

  public java.net.URL getURL(int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getURL(String)");
  }

  public void setURL(String parameterName, java.net.URL val) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setURL(String,URL)");
  }

  public void setNull(String parameterName, int sqlType) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNull(String,int)");
  }

  public void setBoolean(String parameterName, boolean x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBoolean(String,boolean)");
  }

  public void setByte(String parameterName, byte x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setByte(String,byte)");
  }

  public void setShort(String parameterName, short x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setShort(String,short)");
  }

  public void setInt(String parameterName, int x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setInt(String,int)");
  }

  public void setLong(String parameterName, long x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setLong(String,long)");
  }

  public void setFloat(String parameterName, float x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setFloat(String,float)");
  }

  public void setDouble(String parameterName, double x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setDouble(String,double)");
  }

  public void setBigDecimal(String parameterName, BigDecimal x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBigDecimal(String,BigDecimal)");
  }

  public void setString(String parameterName, String x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setString(String,String)");
  }

  public void setBytes(String parameterName, byte x[]) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBytes(String,byte)");
  }

  public void setDate(String parameterName, java.sql.Date x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setDate(String,Date)");
  }

  public void setTime(String parameterName, Time x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setTime(String,Time)");
  }

  public void setTimestamp(String parameterName, Timestamp x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setTimestamp(String,Timestamp)");
  }

  public void setAsciiStream(String parameterName, InputStream x, int length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setAsciiStream(String,InputStream,int)");
  }

  public void setBinaryStream(String parameterName, InputStream x, int length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setBinaryStream(String,InputStream,int)");
  }

  public void setObject(String parameterName, Object x, int targetSqlType, int scale)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject(String,Object,int,int)");
  }

  public void setObject(String parameterName, Object x, int targetSqlType) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject(String,Object,int)");
  }

  public void setObject(String parameterName, Object x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObject(String,Object)");
  }

  public void setCharacterStream(String parameterName, Reader reader, int length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setCharacterStream(String,Reader,int)");
  }

  public void setDate(String parameterName, java.sql.Date x, Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setDate(String,Date,Calendar)");
  }

  public void setTime(String parameterName, Time x, Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setTime(String,Time,Calendar)");
  }

  public void setTimestamp(String parameterName, Timestamp x, Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setTimestamp(String,Timestamp,Calendar)");
  }

  public void setNull(String parameterName, int sqlType, String typeName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNull(String,int,String)");
  }

  public String getString(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getString(String)");
  }

  public boolean getBoolean(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getBoolean(String)");
  }

  public byte getByte(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getByte(String)");
  }

  public short getShort(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getShort(String)");
  }

  public int getInt(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getInt(String)");
  }

  public long getLong(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getLong(String)");
  }

  public float getFloat(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getFloat(String)");
  }

  public double getDouble(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getDouble(String)");
  }

  public byte[] getBytes(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getBytes(String)");
  }

  public java.sql.Date getDate(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getDate(String)");
  }

  public Time getTime(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getTime(String)");
  }

  public Timestamp getTimestamp(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getTimestamp(String)");
  }

  public Object getObject(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getObject(String)");
  }

  public BigDecimal getBigDecimal(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getBigDecimal(String)");
  }

  public Object getObjectImpl(String parameterName, Map<String, Class<?>> map) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getObject(String,Map)");
  }

  public Ref getRef(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getRef(String)");
  }

  public Blob getBlob(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getBlob(String)");
  }

  public Clob getClob(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getClob(String)");
  }

  public Array getArray(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getArray(String)");
  }

  public java.sql.Date getDate(String parameterName, Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getDate(String,Calendar)");
  }

  public Time getTime(String parameterName, Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getTime(String,Calendar)");
  }

  public Timestamp getTimestamp(String parameterName, Calendar cal) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getTimestamp(String,Calendar)");
  }

  public java.net.URL getURL(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getURL(String)");
  }

  public void registerOutParameter(int parameterIndex, int sqlType) throws SQLException {
    // if this isn't 8.1 or we are using protocol version 2 then we don't
    // register the parameter
    switch (sqlType) {
      case Types.BOOLEAN:
        sqlType = Types.BIT;
        break;
      default:

    }
    registerOutParameter(parameterIndex, sqlType, !adjustIndex);
  }

  public void registerOutParameter(int parameterIndex, int sqlType, int scale) throws SQLException {
    // ignore scale for now
    registerOutParameter(parameterIndex, sqlType);
  }
}
