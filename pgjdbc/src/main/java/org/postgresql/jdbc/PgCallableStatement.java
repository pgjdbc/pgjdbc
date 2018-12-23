/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.Driver;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.udt.UdtMap;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

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
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;
import java.util.Map;

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

  /**
   * The type name set in {@link #registerOutParameter(int, int, java.lang.String)}.
   * This array is only created when first needed, since it only applies to the limited
   * subset of statements using the type name feature.
   */
  private String[] functionReturnTypeName;

  /**
   * The scale set in {@link #registerOutParameter(int, int, int)}.
   * This array is only created when first needed, since it only applies to the limited
   * subset of statements using the scale feature.
   */
  private Integer[] functionReturnScale;

  /**
   * The column index within {@link #callResultSet} for each out parameter index.
   */
  private int[] callResultColumnIndex;

  /**
   * The result set for the most recent call, if it had any out parameters registered.
   */
  protected PgResultSet callResultSet;

  // TODO: lastIndex will not be necessary if PgResultSet were to throw SQLException properly on wasNull() before any get*()
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

  public int executeUpdate() throws SQLException {
    if (isFunction) {
      executeWithFlags(0);
      return 0;
    }
    return super.executeUpdate();
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

    PgResultSet rs;
    synchronized (this) {
      checkClosed();
      rs = result.getResultSet();
    }
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
    callResultColumnIndex = new int[preparedParameters.getParameterCount() + 1];

    // move them into the result set
    if (cols > 0) {
      ResultSetMetaData rsMetaData = rs.getMetaData();
      for (int i = 0, j = 0; i < cols; i++, j++) {
        // find the next out parameter, the assumption is that the functionReturnType
        // array will be initialized with 0 and only out parameters will have values
        // other than 0. 0 is the value for java.sql.Types.NULL, which should not
        // conflict
        while (j < functionReturnType.length && functionReturnType[j] == 0) {
          j++;
        }

        callResultColumnIndex[j + 1] = i + 1;
        int columnType = rsMetaData.getColumnType(i + 1);

        int registered = functionReturnType[j];
        if (
            columnType != registered
            // this is here for the sole purpose of passing the cts
            && !(columnType == Types.DOUBLE && registered == Types.REAL)
            //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
            && !(columnType == Types.REF_CURSOR && registered == Types.OTHER)
            // For backwards compatibility reasons we support that ref cursors can be
            // registered with both Types.OTHER and Types.REF_CURSOR so we allow
            // this specific mismatch
            //#endif
            ) {
          throw new PSQLException(GT.tr(
              "A CallableStatement function was executed and the out parameter {0} was of type {1} however type {2} was registered.",
              i + 1, "java.sql.Types=" + columnType, "java.sql.Types=" + registered),
              PSQLState.DATA_TYPE_MISMATCH);
        }
      }
    }
    callResultSet = rs;
    synchronized (this) {
      result = null;
    }
    return false;
  }

  @Override
  protected void closeForNextExecution() throws SQLException {
    ResultSet rs = callResultSet;
    if (rs != null) {
      callResultSet = null;
      rs.close();
    }
    super.closeForNextExecution();
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
  public void registerOutParameter(int parameterIndex, int sqlType)
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
    if (!isFunction) {
      throw new PSQLException(
          GT.tr(
              "This statement does not declare an OUT parameter.  Use '{' ?= call ... '}' to declare one."),
          PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL);
    }
    checkIndex(parameterIndex, false);

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
    // TODO: Can we rely on callResultSet.wasNull() properly throwing SQLException?  It doesn't at this time
    if (lastIndex == 0) {
      throw new PSQLException(GT.tr("wasNull cannot be call before fetching a result."),
          PSQLState.OBJECT_NOT_IN_STATE);
    }
    return callResultSet.wasNull();
  }

  @Override
  public String getString(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.VARCHAR, "String");
    return callResultSet.getString(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public boolean getBoolean(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.BIT, "Boolean");
    return callResultSet.getBoolean(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public byte getByte(int parameterIndex) throws SQLException {
    checkClosed();
    // fake tiny int with smallint
    checkIndex(parameterIndex, Types.SMALLINT, "Byte");
    return callResultSet.getByte(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public short getShort(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.SMALLINT, "Short");
    return callResultSet.getShort(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public int getInt(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.INTEGER, "Int");
    return callResultSet.getInt(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public long getLong(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.BIGINT, "Long");
    return callResultSet.getLong(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public float getFloat(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.REAL, "Float");
    return callResultSet.getFloat(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public double getDouble(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.DOUBLE, "Double");
    return callResultSet.getDouble(callResultColumnIndex[parameterIndex]);
  }

  /**
   * {@inheritDoc}
   *
   * @deprecated use <code>getBigDecimal(int parameterIndex)</code>
   *             or <code>getBigDecimal(String parameterName)</code>
   */
  @Deprecated
  @Override
  public BigDecimal getBigDecimal(int parameterIndex, int scale) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.NUMERIC, "BigDecimal");
    return callResultSet.getBigDecimal(callResultColumnIndex[parameterIndex], scale);
  }

  @Override
  public Object getObject(int parameterIndex, Map<String, Class<?>> map) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex);
    if (functionReturnTypeName != null) {
      String returnType = functionReturnTypeName[parameterIndex - 1];
      if (returnType != null) {
        UdtMap udtMap;
        if (map == null) {
          // https://docs.oracle.com/javase/tutorial/jdbc/basics/sqlcustommapping.html:
          //   "If you do not pass a type map to a method that can accept one, the driver will by default use the type map associated with the connection."
          udtMap = connection.getUdtMap();
        } else {
          udtMap = new UdtMap(map);
        }
        Class<?> customType = udtMap.getTypeMap().get(returnType);
        if (customType != null) {
          return callResultSet.getObjectImpl(callResultColumnIndex[parameterIndex], customType, udtMap);
        }
      }
    }
    return callResultSet.getObject(callResultColumnIndex[parameterIndex], map);
  }

  @Override
  public byte[] getBytes(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.VARBINARY, Types.BINARY, "Bytes");
    return callResultSet.getBytes(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public java.sql.Date getDate(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.DATE, "Date");
    return callResultSet.getDate(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public java.sql.Time getTime(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.TIME, "Time");
    return callResultSet.getTime(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public java.sql.Timestamp getTimestamp(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.TIMESTAMP, "Timestamp");
    return callResultSet.getTimestamp(callResultColumnIndex[parameterIndex]);
  }

  /**
   * This is not defined in {@link CallableStatement}, but is here for the
   * implementation of {@link PgCallableStatementSQLInput#readAsciiStream()}.
   *
   * @param parameterIndex the first parameter is 1, the second is 2,
   *        and so on
   *
   * @return a Java input stream that delivers the database column value
   *         as a stream of one-byte ASCII characters;
   *         if the value is SQL <code>NULL</code>, the
   *         value returned is <code>null</code>
   *
   * @exception SQLException if the parameterIndex is not valid;
   *            if a database access error occurs or
   *            this method is called on a closed <code>CallableStatement</code>
   */
  // TODO: If we can continue to delegate to PgResultSet, and PgCallableStatementSQLInput is
  //       no longer needed, remove this method
  InputStream getAsciiStream(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex);
    return callResultSet.getAsciiStream(callResultColumnIndex[parameterIndex]);
  }

  /**
   * This is not defined in {@link CallableStatement}, but is here for the
   * implementation of {@link PgCallableStatementSQLInput#readBinaryStream()}.
   *
   * @param parameterIndex the first parameter is 1, the second is 2,
   *        and so on
   *
   * @return a Java input stream that delivers the database column value
   *         as a stream of uninterpreted bytes;
   *         if the value is SQL <code>NULL</code>, the value returned is
   *         <code>null</code>
   *
   * @exception SQLException if the parameterIndex is not valid;
   *            if a database access error occurs or
   *            this method is called on a closed <code>CallableStatement</code>
   */
  // TODO: If we can continue to delegate to PgResultSet, and PgCallableStatementSQLInput is
  //       no longer needed, remove this method
  InputStream getBinaryStream(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex);
    return callResultSet.getBinaryStream(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public Object getObject(int parameterIndex) throws SQLException {
    return getObject(parameterIndex, (Map)null);
  }

  /**
   * Helper function for the getXXX calls to check isFunction and index == 1 Compare ALL type fields
   * against the return type.
   *
   * @param parameterIndex parameter index (1-based)
   * @param type1 type 1
   * @param type2 type 2
   * @param type3 type 3
   * @param getName getter name
   * @throws SQLException if something goes wrong
   */
  protected void checkIndex(int parameterIndex, int type1, int type2, int type3, String getName)
      throws SQLException {
    checkIndex(parameterIndex);
    int registered = this.testReturn[parameterIndex - 1];
    if (type1 != registered
        && type2 != registered
        && type3 != registered) {
      throw new PSQLException(
          GT.tr("Parameter of type {0} was registered, but call to get{1} (sqltype={2}, sqltype={3}, or sqltype={4}) was made.",
                  "java.sql.Types=" + registered, getName,
                  "java.sql.Types=" + type1,
                  "java.sql.Types=" + type2,
                  "java.sql.Types=" + type3),
          PSQLState.MOST_SPECIFIC_TYPE_DOES_NOT_MATCH);
    }
  }

  /**
   * Helper function for the getXXX calls to check isFunction and index == 1 Compare BOTH type fields
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
    int registered = this.testReturn[parameterIndex - 1];
    if (type1 != registered
        && type2 != registered) {
      throw new PSQLException(
          GT.tr("Parameter of type {0} was registered, but call to get{1} (sqltype={2} or sqltype={3}) was made.",
                  "java.sql.Types=" + registered, getName,
                  "java.sql.Types=" + type1,
                  "java.sql.Types=" + type2),
          PSQLState.MOST_SPECIFIC_TYPE_DOES_NOT_MATCH);
    }
  }

  /**
   * Helper function for the getXXX calls to check isFunction and index == 1.
   *
   * @param parameterIndex parameter index (1-based)
   * @param type type
   * @param getName getter name
   * @throws SQLException if given index is not valid
   */
  protected void checkIndex(int parameterIndex, int type, String getName) throws SQLException {
    checkIndex(parameterIndex);
    int registered = this.testReturn[parameterIndex - 1];
    if (type != registered) {
      throw new PSQLException(
          GT.tr("Parameter of type {0} was registered, but call to get{1} (sqltype={2}) was made.",
              "java.sql.Types=" + registered, getName,
                  "java.sql.Types=" + type),
          PSQLState.MOST_SPECIFIC_TYPE_DOES_NOT_MATCH);
    }
  }

  private void checkIndex(int parameterIndex) throws SQLException {
    checkIndex(parameterIndex, true);
  }

  /**
   * Helper function for the getXXX calls to check isFunction and index == 1.
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

      if (callResultSet == null) {
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

  /*
   * TODO: Should functionReturnTypeName affect this?  If the out parameter is
   * set to a user-defined data type, does it define the type of the individual elements within the
   * array?
   */
  @Override
  public java.sql.Array getArray(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.ARRAY, "Array");
    return callResultSet.getArray(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public java.math.BigDecimal getBigDecimal(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.NUMERIC, "BigDecimal");
    // Use the scale set, if any
    if (functionReturnScale != null) {
      Integer scale = functionReturnScale[parameterIndex - 1];
      if (scale != null) {
        return callResultSet.getBigDecimal(callResultColumnIndex[parameterIndex], scale);
      }
    }
    return callResultSet.getBigDecimal(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public Blob getBlob(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.BLOB, "Blob");
    return callResultSet.getBlob(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public Clob getClob(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.CLOB, "Clob");
    return callResultSet.getClob(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public Ref getRef(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.REF, "Ref");
    return callResultSet.getRef(callResultColumnIndex[parameterIndex]);
  }

  @Override
  public java.sql.Date getDate(int parameterIndex, java.util.Calendar cal) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.DATE, "Date");
    return callResultSet.getDate(callResultColumnIndex[parameterIndex], cal);
  }

  @Override
  public Time getTime(int parameterIndex, java.util.Calendar cal) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.TIME, "Time");
    return callResultSet.getTime(callResultColumnIndex[parameterIndex], cal);
  }

  @Override
  public Timestamp getTimestamp(int parameterIndex, java.util.Calendar cal) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.TIMESTAMP, "Timestamp");
    return callResultSet.getTimestamp(callResultColumnIndex[parameterIndex], cal);
  }

  @Override
  public void registerOutParameter(int parameterIndex, int sqlType, String typeName)
      throws SQLException {
    registerOutParameter(parameterIndex, sqlType);
    if (functionReturnTypeName == null) {
      functionReturnTypeName = new String[functionReturnType.length];
    }
    functionReturnTypeName[parameterIndex] = typeName;
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

  @Override
  public RowId getRowId(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.ROWID, "RowId");
    return callResultSet.getRowId(callResultColumnIndex[parameterIndex]);
  }

  // TODO: All these get*(String parameterName) methods could trivially be mapped onto callResultSet,
  //       but would this be meaningful as-is?  Or would the set*(String parameterName, ...) need to
  //       be implemented as well?

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

  @Override
  public NClob getNClob(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.NCLOB, "NClob");
    return callResultSet.getNClob(callResultColumnIndex[parameterIndex]);
  }

  public NClob getNClob(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNClob(String)");
  }

  public void setSQLXML(String parameterName, SQLXML xmlObject) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setSQLXML(String, SQLXML)");
  }

  @Override
  public SQLXML getSQLXML(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.SQLXML, "SQLXML");
    return callResultSet.getSQLXML(callResultColumnIndex[parameterIndex]);
  }

  public SQLXML getSQLXML(String parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getSQLXML(String)");
  }

  @Override
  public String getNString(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, "NString");
    return callResultSet.getNString(callResultColumnIndex[parameterIndex]);
  }

  public String getNString(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNString(String)");
  }

  @Override
  public Reader getNCharacterStream(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, "NCharacterStream");
    return callResultSet.getNCharacterStream(callResultColumnIndex[parameterIndex]);
  }

  public Reader getNCharacterStream(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNCharacterStream(String)");
  }

  @Override
  public Reader getCharacterStream(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.VARCHAR, "CharacterStream");
    return callResultSet.getCharacterStream(callResultColumnIndex[parameterIndex]);
  }

  public Reader getCharacterStream(String parameterName) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getCharacterStream(String)");
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Override
  //#endif
  public <T> T getObject(int parameterIndex, Class<T> type) throws SQLException {
    return getObjectImpl(parameterIndex, type, connection.getUdtMap());
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Override
  //#endif
  public <T> T getObject(String parameterName, Class<T> type) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getObject(String, Class<T>)");
  }

  /**
   * The implementation of {@link #getObject(int, java.lang.Class)}, but accepting
   * the currently active type map.  This additional method is required because the
   * provided map is used for type inference.
   *
   * @see #getObject(int, java.lang.Class)
   * @see PgCallableStatementSQLInput#readObject(java.lang.Class)
   */
  // TODO: If we can continue to delegate to PgResultSet, and PgCallableStatementSQLInput is
  //       no longer needed, remove this method and move its implementation to
  //       getObject(int,Class) above.
  <T> T getObjectImpl(int parameterIndex, Class<T> type, UdtMap udtMap) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex);
    // TODO: Is ResultSet going to be OK by delegating to PgResultSet.getObjectImpl?
    // TODO: Why this special case here?
    if (type == ResultSet.class) {
      return type.cast(getObject(parameterIndex));
    }
    if (functionReturnTypeName != null) {
      String returnType = functionReturnTypeName[parameterIndex - 1];
      if (returnType != null) {
        Class<?> customType = udtMap.getTypeMap().get(returnType);
        if (customType != null) {
          // Make sure customType is assignable to type
          if (type.isAssignableFrom(customType)) {
            return type.cast(callResultSet.getObjectImpl(callResultColumnIndex[parameterIndex], customType, udtMap));
          } else {
            throw new PSQLException(GT.tr("Customized type from map {0} -> {1} is not assignable to requested type {2}", returnType, customType.getName(), type.getName()),
                    PSQLState.CANNOT_COERCE);
          }
        }
      }
    }
    return callResultSet.getObjectImpl(callResultColumnIndex[parameterIndex], type, udtMap);
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

  @Override
  public java.net.URL getURL(int parameterIndex) throws SQLException {
    checkClosed();
    checkIndex(parameterIndex, Types.DATALINK, "URL");
    return callResultSet.getURL(callResultColumnIndex[parameterIndex]);
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

  public void setBytes(String parameterName, byte[] x) throws SQLException {
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

  public Object getObject(String parameterName, Map<String, Class<?>> map) throws SQLException {
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

  @Override
  public void registerOutParameter(int parameterIndex, int sqlType, int scale) throws SQLException {
    registerOutParameter(parameterIndex, sqlType);
    if (functionReturnScale == null) {
      functionReturnScale = new Integer[functionReturnType.length];
    }
    functionReturnScale[parameterIndex] = scale;
  }
}
