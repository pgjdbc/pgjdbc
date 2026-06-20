/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.Driver;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.index.qual.Positive;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Date;
import java.sql.JDBCType;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
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
  private int lastIndex;
  // Original JDBC escape SQL ("{ ? = call f(...) }"), kept to resolve parameter names.
  private final String jdbcSql;

  PgCallableStatement(PgConnection connection, String sql, int rsType, int rsConcurrency,
      int rsHoldability) throws SQLException {
    super(connection, connection.borrowCallableQuery(sql), rsType, rsConcurrency, rsHoldability);
    this.jdbcSql = sql;
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
    try (ResourceLock ignore = lock.obtain()) {
      boolean hasResultSet = super.executeWithFlags(flags);
      int[] functionReturnType = this.functionReturnType;
      if (!isFunction || !returnTypeSet || functionReturnType == null) {
        return hasResultSet;
      }

      // If we are executing and there are out parameters
      // callable statement function set the return data
      if (!hasResultSet) {
        throw new PSQLException(GT.tr("A CallableStatement was executed with nothing returned."),
            PSQLState.NO_DATA);
      }

      ResultSet rs = castNonNull(getResultSet());
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
            throw new PSQLException(GT.tr(
                "A CallableStatement function was executed and the out parameter {0} was of type {1} however type {2} was registered.",
                i + 1, "java.sql.Types=" + columnType, "java.sql.Types=" + functionReturnType[j]),
                PSQLState.DATA_TYPE_MISMATCH);
          }
        }

      }
      rs.close();
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
      throw new PSQLException(
          GT.tr(
              "This statement does not declare an OUT parameter.  Use '{' ?= call ... '}' to declare one."),
          PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL);
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
      throw new PSQLException(GT.tr("wasNull cannot be call before fetching a result."),
          PSQLState.OBJECT_NOT_IN_STATE);
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
    return BooleanTypeUtil.castToBoolean(result);
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
  @SuppressWarnings("deprecation")
  public @Nullable BigDecimal getBigDecimal(@Positive int parameterIndex, int scale) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.NUMERIC, "BigDecimal");
    return (@Nullable BigDecimal) result;
  }

  @Override
  public byte @Nullable [] getBytes(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.VARBINARY, Types.BINARY, "Bytes");
    return (byte @Nullable []) result;
  }

  @Override
  public @Nullable Date getDate(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.DATE, "Date");
    return (@Nullable Date) result;
  }

  @Override
  public @Nullable Time getTime(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.TIME, "Time");
    return (@Nullable Time) result;
  }

  @Override
  public @Nullable Timestamp getTimestamp(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.TIMESTAMP, "Timestamp");
    return (@Nullable Timestamp) result;
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
      throw new PSQLException(
          GT.tr("Parameter of type {0} was registered, but call to get{1} (sqltype={2}) was made.",
                  "java.sql.Types=" + testReturn, getName,
                  "java.sql.Types=" + type1),
          PSQLState.MOST_SPECIFIC_TYPE_DOES_NOT_MATCH);
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
      throw new PSQLException(
          GT.tr("Parameter of type {0} was registered, but call to get{1} (sqltype={2}) was made.",
              "java.sql.Types=" + testReturn, getName,
                  "java.sql.Types=" + type),
          PSQLState.MOST_SPECIFIC_TYPE_DOES_NOT_MATCH);
    }
    return result;
  }

  private @Nullable Object getCallResult(@Positive int parameterIndex) throws SQLException {
    checkClosed();

    if (!isFunction) {
      throw new PSQLException(
          GT.tr(
              "A CallableStatement was declared, but no call to registerOutParameter(1, <some type>) was made."),
          PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL);
    }

    if (!returnTypeSet) {
      throw new PSQLException(GT.tr("No function outputs were registered."),
          PSQLState.OBJECT_NOT_IN_STATE);
    }

    @Nullable Object @Nullable [] callResult = this.callResult;
    if (callResult == null) {
      throw new PSQLException(
          GT.tr("Results cannot be retrieved from a CallableStatement before it is executed."),
          PSQLState.NO_DATA);
    }

    lastIndex = parameterIndex;
    return callResult[parameterIndex - 1];
  }

  /**
   * Resolves a JDBC parameter name to its 1-based parameter index.
   *
   * <p>Names are matched case-insensitively (PostgreSQL folds unquoted identifiers to lower
   * case) against the routine's argument names, looked up once in {@code pg_catalog.pg_proc} and
   * cached on the {@link org.postgresql.core.CachedQuery} so that repeated executions of the same
   * callable SQL do not re-query the catalog. The cached mapping is stamped with the connection's
   * type-cache epoch and rebuilt once a DDL statement bumps it, since the routine's signature may
   * have changed. The catalog is the single source of truth here so that the same name resolves
   * to the same index whether it is used before execution (e.g.
   * {@code registerOutParameter(String, ...)}, {@code setXXX(String, ...)}) or after it (e.g.
   * {@code getXXX(String)}).</p>
   *
   * @param parameterName the parameter name
   * @return the 1-based parameter index
   * @throws SQLException if the name cannot be resolved to a single parameter
   */
  private int resolveParameterIndex(String parameterName) throws SQLException {
    checkClosed();
    int typeCacheEpoch = connection.getTypeCacheEpoch();
    Map<String, Integer> names = preparedQuery.getCallableParameterNameIndexMap(typeCacheEpoch);
    if (names == null) {
      names = resolveParameterNamesFromCatalog();
      preparedQuery.setCallableParameterNameIndexMap(names, typeCacheEpoch);
    }
    Integer idx = names.get(parameterName.toLowerCase(Locale.US));
    if (idx == null) {
      throw new PSQLException(GT.tr("No parameter named {0} was found.", parameterName),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    return idx;
  }

  /**
   * Builds a (folded name -&gt; 1-based JDBC index) map from {@code pg_catalog.pg_proc} for the
   * routine being called.
   *
   * <p>For the {@code { call proc(...) }} form every argument — IN, OUT, INOUT, TABLE — occupies
   * its declaration-order JDBC position (1, 2, 3, …), mirroring the positional API where, for
   * example, {@code { call f(?, ?) }} over {@code f(a INOUT, b OUT)} registers indexes 1 and 2.</p>
   *
   * <p>For the {@code { ? = call f(...) }} form the leading {@code ? =} return placeholder takes
   * index 1: the first pure OUT/TABLE argument (or the scalar return) maps to index 1 and the
   * input-bearing arguments (IN, INOUT, VARIADIC) map to 2, 3, … in declaration order. INOUT
   * arguments are treated as inputs in this form.</p>
   *
   * <p>This queries {@code proargnames}/{@code proargmodes} directly rather than reusing
   * {@link java.sql.DatabaseMetaData#getProcedureColumns} on purpose: those metadata calls take
   * the schema and routine name as separate {@code LIKE} patterns, so reusing them would require
   * splitting and LIKE-escaping the (possibly quoted, possibly schema-qualified) name, filtering
   * the result rows by {@code search_path}, and disambiguating overloads by hand. A {@code regproc}
   * cast resolves the name with the same rules as the call itself, which is both shorter and more
   * accurate here; it raises on an absent or overloaded name, which is mapped to an actionable
   * "use a positional parameter index" error.</p>
   */
  private Map<String, Integer> resolveParameterNamesFromCatalog() throws SQLException {
    String functionName = extractRoutineName(jdbcSql);
    if (functionName == null) {
      throw new PSQLException(
          GT.tr("Unable to determine the routine name to resolve parameter names; "
              + "use a positional parameter index instead."),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    String @Nullable [] argNames = null;
    String @Nullable [] argModes = null;
    boolean resolved = false;
    // Resolve the (possibly quoted, schema-qualified) name through the same search_path and quoting
    // rules as the call itself by casting it to regproc. The cast is available on every supported
    // server, unlike pg_catalog.to_regproc (9.4+); it raises rather than returning NULL when the
    // name is absent or overloaded, so the catch below maps that to the actionable message.
    try (PreparedStatement ps = connection.prepareStatement(
        "SELECT proargnames, proargmodes FROM pg_catalog.pg_proc "
            + "WHERE oid = ?::pg_catalog.regproc")) {
      ps.setString(1, functionName);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          resolved = true;
          Array namesArray = rs.getArray("proargnames");
          if (namesArray != null) {
            argNames = (String[]) namesArray.getArray();
          }
          Array modesArray = rs.getArray("proargmodes");
          if (modesArray != null) {
            argModes = (String[]) modesArray.getArray();
          }
        }
      }
    } catch (SQLException e) {
      // The regproc cast raises 'function "x" does not exist' (absent) or 'more than one function
      // named "x"' (overloaded); both mean the name is not a single routine.
      throw new PSQLException(
          GT.tr("Unable to resolve routine {0} to a single function; it may be overloaded "
              + "or absent. Use a positional parameter index instead.", functionName),
          PSQLState.INVALID_PARAMETER_VALUE, e);
    }
    if (!resolved) {
      throw new PSQLException(
          GT.tr("Unable to resolve routine {0} to a single function; it may be overloaded "
              + "or absent. Use a positional parameter index instead.", functionName),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    Map<String, Integer> result = new HashMap<>();
    if (argNames == null) {
      return result;
    }
    if (!hasReturnPlaceholder(jdbcSql)) {
      // { call proc(...) }: every argument occupies its declaration-order JDBC position,
      // regardless of mode, matching the positional API.
      for (int i = 0; i < argNames.length; i++) {
        String name = argNames[i];
        if (name != null && !name.isEmpty()) {
          result.putIfAbsent(name.toLowerCase(Locale.US), i + 1);
        }
      }
      return result;
    }
    // { ? = call f(...) }: index 1 is the leading return placeholder (the first pure OUT/TABLE
    // result, or the scalar return); input-bearing arguments map to 2, 3, … in declaration order.
    int nextInIndex = 2;
    boolean returnAssigned = false;
    for (int i = 0; i < argNames.length; i++) {
      String name = argNames[i];
      char mode = argModes == null || argModes[i] == null || argModes[i].isEmpty()
          ? 'i' : argModes[i].charAt(0);
      if (mode == 'o' || mode == 't') {
        if (!returnAssigned) {
          if (name != null && !name.isEmpty()) {
            result.putIfAbsent(name.toLowerCase(Locale.US), 1);
          }
          returnAssigned = true;
        }
        continue;
      }
      // Input-bearing argument (IN, INOUT, VARIADIC). Advance the position even for an unnamed
      // argument: PostgreSQL still emits an empty proargnames entry for it, and skipping it
      // would shift every following name by one.
      int index = nextInIndex++;
      if (name != null && !name.isEmpty()) {
        result.putIfAbsent(name.toLowerCase(Locale.US), index);
      }
    }
    return result;
  }

  /**
   * Extracts the routine name (optionally schema-qualified and/or quoted) from the JDBC escape
   * SQL. Schema, quoting and {@code search_path} resolution are left to the {@code regproc} cast.
   *
   * @return the routine name, or {@code null} if it cannot be located
   */
  private static @Nullable String extractRoutineName(String jdbcSql) {
    String lower = jdbcSql.toLowerCase(Locale.US);
    int from = 0;
    while (true) {
      int call = lower.indexOf("call", from);
      if (call < 0) {
        return null;
      }
      boolean leftBoundary = call == 0 || !Character.isLetterOrDigit(lower.charAt(call - 1));
      int after = call + 4;
      boolean rightBoundary = after >= lower.length() || !Character.isLetterOrDigit(lower.charAt(after));
      if (leftBoundary && rightBoundary) {
        int p = after;
        while (p < jdbcSql.length() && Character.isWhitespace(jdbcSql.charAt(p))) {
          p++;
        }
        int start = p;
        boolean inQuotes = false;
        while (p < jdbcSql.length()) {
          char ch = jdbcSql.charAt(p);
          if (ch == '"') {
            inQuotes = !inQuotes;
          } else if (!inQuotes && (ch == '(' || ch == '}' || Character.isWhitespace(ch))) {
            break;
          }
          p++;
        }
        String name = jdbcSql.substring(start, p).trim();
        return name.isEmpty() ? null : name;
      }
      from = call + 4;
    }
  }

  /**
   * Returns {@code true} when the call uses the {@code { ? = call ... }} form, i.e. a return
   * placeholder precedes the {@code call} keyword.
   */
  private static boolean hasReturnPlaceholder(String jdbcSql) {
    String lower = jdbcSql.toLowerCase(Locale.US);
    int call = lower.indexOf("call");
    if (call < 0) {
      return false;
    }
    return jdbcSql.lastIndexOf('?', call) >= 0;
  }

  @Override
  protected BatchResultHandler createBatchHandler(Query[] queries,
      @Nullable ParameterList[] parameterLists) {
    return new CallableBatchResultHandler(this, queries, parameterLists);
  }

  @Override
  public @Nullable Array getArray(int i) throws SQLException {
    Object result = checkIndex(i, Types.ARRAY, "Array");
    return (Array) result;
  }

  @Override
  public @Nullable BigDecimal getBigDecimal(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.NUMERIC, "BigDecimal");
    return (BigDecimal) result;
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
  public @Nullable Date getDate(int i, @Nullable Calendar cal) throws SQLException {
    Object result = checkIndex(i, Types.DATE, "Date");

    if (result == null) {
      return null;
    }

    return getTimestampUtils().toDate(cal, result.toString());
  }

  @Override
  public @Nullable Time getTime(int i, @Nullable Calendar cal) throws SQLException {
    Object result = checkIndex(i, Types.TIME, "Time");

    if (result == null) {
      return null;
    }

    return getTimestampUtils().toTime(cal, result.toString());
  }

  @Override
  public @Nullable Timestamp getTimestamp(int i, @Nullable Calendar cal) throws SQLException {
    Object result = checkIndex(i, Types.TIMESTAMP, "Timestamp");

    if (result == null) {
      return null;
    }

    return getTimestampUtils().toTimestamp(cal, result.toString());
  }

  @Override
  public void registerOutParameter(@Positive int parameterIndex, int sqlType, String typeName)
      throws SQLException {
    // The composite/STRUCT type name is only needed to decode the OUT value into a user-supplied
    // SQLData class, which is not supported here yet. Register the parameter by its SQL type and
    // ignore the type name.
    registerOutParameter(parameterIndex, sqlType);
  }

  @Override
  public void setObject(String parameterName, @Nullable Object x, SQLType targetSqlType,
      int scaleOrLength) throws SQLException {
    // setObject(int, Object, SQLType, int) is not implemented yet, so resolving the name first
    // would only add a catalog round-trip before failing. Fail fast instead.
    throw Driver.notImplemented(this.getClass(), "setObject");
  }

  @Override
  public void setObject(String parameterName, @Nullable Object x, SQLType targetSqlType)
      throws SQLException {
    // See setObject(String, Object, SQLType, int) above.
    throw Driver.notImplemented(this.getClass(), "setObject");
  }

  /**
   * Maps an {@link SQLType} to its {@code java.sql.Types} code.
   *
   * <p>Standard {@link JDBCType} values report their own code; a PostgreSQL-specific {@link SQLType}
   * is mapped by name, falling back to {@link Types#OTHER} for types without a dedicated JDBC code
   * (composites, enums, and so on).</p>
   */
  private static int getSqlTypeCode(SQLType sqlType) {
    if (sqlType instanceof JDBCType) {
      return ((JDBCType) sqlType).getVendorTypeNumber();
    }
    switch (sqlType.getName().toLowerCase(Locale.ROOT)) {
      case "int2":
      case "smallint":
        return Types.SMALLINT;
      case "int4":
      case "integer":
      case "serial":
        return Types.INTEGER;
      case "int8":
      case "bigint":
      case "bigserial":
        return Types.BIGINT;
      case "float4":
      case "real":
        return Types.REAL;
      case "float8":
      case "double precision":
        return Types.DOUBLE;
      case "numeric":
      case "decimal":
        return Types.NUMERIC;
      case "varchar":
      case "character varying":
      case "text":
        return Types.VARCHAR;
      case "bpchar":
      case "char":
      case "character":
        return Types.CHAR;
      case "bool":
      case "boolean":
        return Types.BOOLEAN;
      case "date":
        return Types.DATE;
      case "time":
      case "time without time zone":
        return Types.TIME;
      case "timetz":
      case "time with time zone":
        return Types.TIME_WITH_TIMEZONE;
      case "timestamp":
      case "timestamp without time zone":
        return Types.TIMESTAMP;
      case "timestamptz":
      case "timestamp with time zone":
        return Types.TIMESTAMP_WITH_TIMEZONE;
      case "bytea":
        return Types.BINARY;
      case "bit":
        return Types.BIT;
      case "xml":
        return Types.SQLXML;
      case "refcursor":
        return Types.REF_CURSOR;
      default:
        return Types.OTHER;
    }
  }

  @Override
  public void registerOutParameter(@Positive int parameterIndex, SQLType sqlType)
      throws SQLException {
    registerOutParameter(parameterIndex, getSqlTypeCode(sqlType));
  }

  @Override
  public void registerOutParameter(@Positive int parameterIndex, SQLType sqlType, int scale)
      throws SQLException {
    registerOutParameter(parameterIndex, getSqlTypeCode(sqlType), scale);
  }

  @Override
  public void registerOutParameter(@Positive int parameterIndex, SQLType sqlType, String typeName)
      throws SQLException {
    registerOutParameter(parameterIndex, getSqlTypeCode(sqlType), typeName);
  }

  @Override
  public void registerOutParameter(String parameterName, SQLType sqlType)
      throws SQLException {
    registerOutParameter(resolveParameterIndex(parameterName), sqlType);
  }

  @Override
  public void registerOutParameter(String parameterName, SQLType sqlType, int scale)
      throws SQLException {
    registerOutParameter(resolveParameterIndex(parameterName), sqlType, scale);
  }

  @Override
  public void registerOutParameter(String parameterName, SQLType sqlType, String typeName)
      throws SQLException {
    registerOutParameter(resolveParameterIndex(parameterName), sqlType, typeName);
  }

  @Override
  public @Nullable RowId getRowId(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getRowId(int)");
  }

  @Override
  public @Nullable RowId getRowId(String parameterName) throws SQLException {
    return getRowId(resolveParameterIndex(parameterName));
  }

  @Override
  public void setRowId(String parameterName, @Nullable RowId x) throws SQLException {
    setRowId(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setNString(String parameterName, @Nullable String value) throws SQLException {
    setNString(resolveParameterIndex(parameterName), value);
  }

  @Override
  public void setNCharacterStream(String parameterName, @Nullable Reader value, long length)
      throws SQLException {
    setNCharacterStream(resolveParameterIndex(parameterName), value, length);
  }

  @Override
  public void setNCharacterStream(String parameterName, @Nullable Reader value) throws SQLException {
    setNCharacterStream(resolveParameterIndex(parameterName), value);
  }

  @Override
  public void setCharacterStream(String parameterName, @Nullable Reader value, long length)
      throws SQLException {
    setCharacterStream(resolveParameterIndex(parameterName), value, length);
  }

  @Override
  public void setCharacterStream(String parameterName, @Nullable Reader value) throws SQLException {
    setCharacterStream(resolveParameterIndex(parameterName), value);
  }

  @Override
  public void setBinaryStream(String parameterName, @Nullable InputStream value, long length)
      throws SQLException {
    setBinaryStream(resolveParameterIndex(parameterName), value, length);
  }

  @Override
  public void setBinaryStream(String parameterName, @Nullable InputStream value) throws SQLException {
    setBinaryStream(resolveParameterIndex(parameterName), value);
  }

  @Override
  public void setAsciiStream(String parameterName, @Nullable InputStream value, long length)
      throws SQLException {
    setAsciiStream(resolveParameterIndex(parameterName), value, length);
  }

  @Override
  public void setAsciiStream(String parameterName, @Nullable InputStream value) throws SQLException {
    setAsciiStream(resolveParameterIndex(parameterName), value);
  }

  @Override
  public void setNClob(String parameterName, @Nullable NClob value) throws SQLException {
    setNClob(resolveParameterIndex(parameterName), value);
  }

  @Override
  public void setClob(String parameterName, @Nullable Reader reader, long length) throws SQLException {
    setClob(resolveParameterIndex(parameterName), reader, length);
  }

  @Override
  public void setClob(String parameterName, @Nullable Reader reader) throws SQLException {
    setClob(resolveParameterIndex(parameterName), reader);
  }

  @Override
  public void setBlob(String parameterName, @Nullable InputStream inputStream, long length)
      throws SQLException {
    setBlob(resolveParameterIndex(parameterName), inputStream, length);
  }

  @Override
  public void setBlob(String parameterName, @Nullable InputStream inputStream) throws SQLException {
    setBlob(resolveParameterIndex(parameterName), inputStream);
  }

  @Override
  public void setBlob(String parameterName, @Nullable Blob x) throws SQLException {
    setBlob(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setClob(String parameterName, @Nullable Clob x) throws SQLException {
    setClob(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setNClob(String parameterName, @Nullable Reader reader, long length) throws SQLException {
    setNClob(resolveParameterIndex(parameterName), reader, length);
  }

  @Override
  public void setNClob(String parameterName, @Nullable Reader reader) throws SQLException {
    setNClob(resolveParameterIndex(parameterName), reader);
  }

  @Override
  public @Nullable NClob getNClob(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNClob(int)");
  }

  @Override
  public @Nullable NClob getNClob(String parameterName) throws SQLException {
    return getNClob(resolveParameterIndex(parameterName));
  }

  @Override
  public void setSQLXML(String parameterName, @Nullable SQLXML xmlObject) throws SQLException {
    setSQLXML(resolveParameterIndex(parameterName), xmlObject);
  }

  @Override
  public @Nullable SQLXML getSQLXML(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.SQLXML, "SQLXML");
    return (SQLXML) result;
  }

  @Override
  public @Nullable SQLXML getSQLXML(String parameterName) throws SQLException {
    return getSQLXML(resolveParameterIndex(parameterName));
  }

  @Override
  public String getNString(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNString(int)");
  }

  @Override
  public @Nullable String getNString(String parameterName) throws SQLException {
    return getNString(resolveParameterIndex(parameterName));
  }

  @Override
  public @Nullable Reader getNCharacterStream(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getNCharacterStream(int)");
  }

  @Override
  public @Nullable Reader getNCharacterStream(String parameterName) throws SQLException {
    return getNCharacterStream(resolveParameterIndex(parameterName));
  }

  @Override
  public @Nullable Reader getCharacterStream(@Positive int parameterIndex) throws SQLException {
    Object result = checkIndex(parameterIndex, Types.VARCHAR, "String");
    if (result == null) {
      return null;
    }
    return new StringReader((String) result);
  }

  @Override
  public @Nullable Reader getCharacterStream(String parameterName) throws SQLException {
    return getCharacterStream(resolveParameterIndex(parameterName));
  }

  @Override
  public <T> @Nullable T getObject(@Positive int parameterIndex, Class<T> type)
      throws SQLException {
    if (type == ResultSet.class) {
      return type.cast(getObject(parameterIndex));
    }
    throw new PSQLException(GT.tr("Unsupported type conversion to {0}.", type),
            PSQLState.INVALID_PARAMETER_VALUE);
  }

  @Override
  public <T> @Nullable T getObject(String parameterName, Class<T> type) throws SQLException {
    return getObject(resolveParameterIndex(parameterName), type);
  }

  @Override
  public void registerOutParameter(String parameterName, int sqlType) throws SQLException {
    registerOutParameter(resolveParameterIndex(parameterName), sqlType);
  }

  @Override
  public void registerOutParameter(String parameterName, int sqlType, int scale)
      throws SQLException {
    registerOutParameter(resolveParameterIndex(parameterName), sqlType, scale);
  }

  @Override
  public void registerOutParameter(String parameterName, int sqlType, String typeName)
      throws SQLException {
    registerOutParameter(resolveParameterIndex(parameterName), sqlType, typeName);
  }

  @Override
  public @Nullable URL getURL(@Positive int parameterIndex) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getURL(String)");
  }

  @Override
  public void setURL(String parameterName, @Nullable URL val) throws SQLException {
    setURL(resolveParameterIndex(parameterName), val);
  }

  @Override
  public void setNull(String parameterName, int sqlType) throws SQLException {
    setNull(resolveParameterIndex(parameterName), sqlType);
  }

  @Override
  public void setBoolean(String parameterName, boolean x) throws SQLException {
    setBoolean(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setByte(String parameterName, byte x) throws SQLException {
    setByte(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setShort(String parameterName, short x) throws SQLException {
    setShort(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setInt(String parameterName, int x) throws SQLException {
    setInt(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setLong(String parameterName, long x) throws SQLException {
    setLong(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setFloat(String parameterName, float x) throws SQLException {
    setFloat(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setDouble(String parameterName, double x) throws SQLException {
    setDouble(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setBigDecimal(String parameterName, @Nullable BigDecimal x) throws SQLException {
    setBigDecimal(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setString(String parameterName, @Nullable String x) throws SQLException {
    setString(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setBytes(String parameterName, byte @Nullable [] x) throws SQLException {
    setBytes(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setDate(String parameterName, @Nullable Date x) throws SQLException {
    setDate(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setTime(String parameterName, @Nullable Time x) throws SQLException {
    setTime(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setTimestamp(String parameterName, @Nullable Timestamp x) throws SQLException {
    setTimestamp(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setAsciiStream(String parameterName, @Nullable InputStream x, int length) throws SQLException {
    setAsciiStream(resolveParameterIndex(parameterName), x, length);
  }

  @Override
  public void setBinaryStream(String parameterName, @Nullable InputStream x, int length) throws SQLException {
    setBinaryStream(resolveParameterIndex(parameterName), x, length);
  }

  @Override
  public void setObject(String parameterName, @Nullable Object x, int targetSqlType, int scale)
      throws SQLException {
    setObject(resolveParameterIndex(parameterName), x, targetSqlType, scale);
  }

  @Override
  public void setObject(String parameterName, @Nullable Object x, int targetSqlType) throws SQLException {
    setObject(resolveParameterIndex(parameterName), x, targetSqlType);
  }

  @Override
  public void setObject(String parameterName, @Nullable Object x) throws SQLException {
    setObject(resolveParameterIndex(parameterName), x);
  }

  @Override
  public void setCharacterStream(String parameterName, @Nullable Reader reader, int length)
      throws SQLException {
    setCharacterStream(resolveParameterIndex(parameterName), reader, length);
  }

  @Override
  public void setDate(String parameterName, @Nullable Date x, @Nullable Calendar cal) throws SQLException {
    setDate(resolveParameterIndex(parameterName), x, cal);
  }

  @Override
  public void setTime(String parameterName, @Nullable Time x, @Nullable Calendar cal) throws SQLException {
    setTime(resolveParameterIndex(parameterName), x, cal);
  }

  @Override
  public void setTimestamp(String parameterName, @Nullable Timestamp x, @Nullable Calendar cal) throws SQLException {
    setTimestamp(resolveParameterIndex(parameterName), x, cal);
  }

  @Override
  public void setNull(String parameterName, int sqlType, String typeName) throws SQLException {
    setNull(resolveParameterIndex(parameterName), sqlType, typeName);
  }

  @Override
  public @Nullable String getString(String parameterName) throws SQLException {
    return getString(resolveParameterIndex(parameterName));
  }

  @Override
  public boolean getBoolean(String parameterName) throws SQLException {
    return getBoolean(resolveParameterIndex(parameterName));
  }

  @Override
  public byte getByte(String parameterName) throws SQLException {
    return getByte(resolveParameterIndex(parameterName));
  }

  @Override
  public short getShort(String parameterName) throws SQLException {
    return getShort(resolveParameterIndex(parameterName));
  }

  @Override
  public int getInt(String parameterName) throws SQLException {
    return getInt(resolveParameterIndex(parameterName));
  }

  @Override
  public long getLong(String parameterName) throws SQLException {
    return getLong(resolveParameterIndex(parameterName));
  }

  @Override
  public float getFloat(String parameterName) throws SQLException {
    return getFloat(resolveParameterIndex(parameterName));
  }

  @Override
  public double getDouble(String parameterName) throws SQLException {
    return getDouble(resolveParameterIndex(parameterName));
  }

  @Override
  public byte @Nullable [] getBytes(String parameterName) throws SQLException {
    return getBytes(resolveParameterIndex(parameterName));
  }

  @Override
  public @Nullable Date getDate(String parameterName) throws SQLException {
    return getDate(resolveParameterIndex(parameterName));
  }

  @Override
  public @Nullable Time getTime(String parameterName) throws SQLException {
    return getTime(resolveParameterIndex(parameterName));
  }

  @Override
  public @Nullable Timestamp getTimestamp(String parameterName) throws SQLException {
    return getTimestamp(resolveParameterIndex(parameterName));
  }

  @Override
  public @Nullable Object getObject(String parameterName) throws SQLException {
    return getObject(resolveParameterIndex(parameterName));
  }

  @Override
  public @Nullable BigDecimal getBigDecimal(String parameterName) throws SQLException {
    return getBigDecimal(resolveParameterIndex(parameterName));
  }

  public @Nullable Object getObjectImpl(String parameterName, @Nullable Map<String, Class<?>> map) throws SQLException {
    return getObjectImpl(resolveParameterIndex(parameterName), map);
  }

  @Override
  public @Nullable Ref getRef(String parameterName) throws SQLException {
    return getRef(resolveParameterIndex(parameterName));
  }

  @Override
  public @Nullable Blob getBlob(String parameterName) throws SQLException {
    return getBlob(resolveParameterIndex(parameterName));
  }

  @Override
  public @Nullable Clob getClob(String parameterName) throws SQLException {
    return getClob(resolveParameterIndex(parameterName));
  }

  @Override
  public @Nullable Array getArray(String parameterName) throws SQLException {
    return getArray(resolveParameterIndex(parameterName));
  }

  @Override
  public @Nullable Date getDate(String parameterName, @Nullable Calendar cal) throws SQLException {
    return getDate(resolveParameterIndex(parameterName), cal);
  }

  @Override
  public @Nullable Time getTime(String parameterName, @Nullable Calendar cal) throws SQLException {
    return getTime(resolveParameterIndex(parameterName), cal);
  }

  @Override
  public @Nullable Timestamp getTimestamp(String parameterName, @Nullable Calendar cal) throws SQLException {
    return getTimestamp(resolveParameterIndex(parameterName), cal);
  }

  @Override
  public @Nullable URL getURL(String parameterName) throws SQLException {
    return getURL(resolveParameterIndex(parameterName));
  }

  @Override
  public void registerOutParameter(@Positive int parameterIndex, int sqlType, int scale) throws SQLException {
    // ignore scale for now
    registerOutParameter(parameterIndex, sqlType);
  }
}
