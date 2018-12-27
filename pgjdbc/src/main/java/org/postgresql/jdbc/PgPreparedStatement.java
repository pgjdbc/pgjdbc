/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.CachedQuery;
import org.postgresql.core.EnumMode;
import org.postgresql.core.Oid;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.TypeInfo;
import org.postgresql.core.v3.BatchedQuery;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.udt.SingleAttributeSQLOutputHelper;
import org.postgresql.udt.UdtMap;
import org.postgresql.udt.ValueAccessHelper;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.HStoreConverter;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGTime;
import org.postgresql.util.PGTimestamp;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ReaderInputStream;

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
import java.sql.SQLData;
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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

class PgPreparedStatement extends PgStatement implements PreparedStatement {

  // TODO: Should we use the connection logger whenever possible?
  private static final Logger LOGGER = Logger.getLogger(PgPreparedStatement.class.getName());

  protected final CachedQuery preparedQuery; // Query fragments for prepared statement.
  protected final ParameterList preparedParameters; // Parameter values for prepared statement.

  private TimeZone defaultTimeZone;

  PgPreparedStatement(PgConnection connection, String sql, int rsType, int rsConcurrency,
      int rsHoldability) throws SQLException {
    this(connection, connection.borrowQuery(sql), rsType, rsConcurrency, rsHoldability);
  }

  PgPreparedStatement(PgConnection connection, CachedQuery query, int rsType,
      int rsConcurrency, int rsHoldability) throws SQLException {
    super(connection, rsType, rsConcurrency, rsHoldability);

    this.preparedQuery = query;
    this.preparedParameters = this.preparedQuery.query.createParameterList();
    // TODO: this.wantsGeneratedKeysAlways = true;

    setPoolable(true); // As per JDBC spec: prepared and callable statements are poolable by
  }

  public java.sql.ResultSet executeQuery(String p_sql) throws SQLException {
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
  public java.sql.ResultSet executeQuery() throws SQLException {
    if (!executeWithFlags(0)) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    return getSingleResultSet();
  }

  public int executeUpdate(String p_sql) throws SQLException {
    throw new PSQLException(
        GT.tr("Can''t use query methods that take a query string on a PreparedStatement."),
        PSQLState.WRONG_OBJECT_TYPE);
  }

  public int executeUpdate() throws SQLException {
    executeWithFlags(QueryExecutor.QUERY_NO_RESULTS);

    return getNoResultUpdateCount();
  }

  public boolean execute(String p_sql) throws SQLException {
    throw new PSQLException(
        GT.tr("Can''t use query methods that take a query string on a PreparedStatement."),
        PSQLState.WRONG_OBJECT_TYPE);
  }

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

  protected boolean isOneShotQuery(CachedQuery cachedQuery) {
    if (cachedQuery == null) {
      cachedQuery = preparedQuery;
    }
    return super.isOneShotQuery(cachedQuery);
  }

  @Override
  public void closeImpl() throws SQLException {
    if (preparedQuery != null) {
      // TODO: connection could be PgConnection to avoid this cast
      ((PgConnection) connection).releaseQuery(preparedQuery);
    }
  }

  protected void setNullImpl(int parameterIndex, String pgType, int scale, int sqlType) throws SQLException {
    checkClosed();

    int selectedScale = -1;
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
        selectedScale = scale;
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
      //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
      case Types.TIME_WITH_TIMEZONE:
      case Types.TIMESTAMP_WITH_TIMEZONE:
      //#endif
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
    preparedParameters.setNull(parameterIndex, pgType, selectedScale, oid);
  }

  @Override
  public final void setNull(int parameterIndex, int sqlType) throws SQLException {
    setNullImpl(parameterIndex, null, -1, sqlType);
  }

  protected void setBooleanImpl(int parameterIndex, String pgType, boolean x) throws SQLException {
    checkClosed();
    // The key words TRUE and FALSE are the preferred (SQL-compliant) usage.
    bindLiteral(parameterIndex, pgType, -1, x ? "TRUE" : "FALSE", Oid.BOOL);
  }

  @Override
  public final void setBoolean(int parameterIndex, boolean x) throws SQLException {
    setBooleanImpl(parameterIndex, null, x);
  }

  protected void setByteImpl(int parameterIndex, String pgType, byte x) throws SQLException {
    setShortImpl(parameterIndex, pgType, x);
  }

  @Override
  public final void setByte(int parameterIndex, byte x) throws SQLException {
    setByteImpl(parameterIndex, null, x);
  }

  protected void setShortImpl(int parameterIndex, String pgType, short x) throws SQLException {
    checkClosed();
    if (connection.binaryTransferSend(Oid.INT2)) {
      byte[] val = new byte[2];
      ByteConverter.int2(val, 0, x);
      bindBytes(parameterIndex, pgType, val, Oid.INT2);
      return;
    }
    bindLiteral(parameterIndex, pgType, -1, Integer.toString(x), Oid.INT2);
  }

  @Override
  public final void setShort(int parameterIndex, short x) throws SQLException {
    setShortImpl(parameterIndex, null, x);
  }

  protected void setIntImpl(int parameterIndex, String pgType, int x) throws SQLException {
    checkClosed();
    if (connection.binaryTransferSend(Oid.INT4)) {
      byte[] val = new byte[4];
      ByteConverter.int4(val, 0, x);
      bindBytes(parameterIndex, pgType, val, Oid.INT4);
      return;
    }
    bindLiteral(parameterIndex, pgType, -1, Integer.toString(x), Oid.INT4);
  }

  @Override
  public final void setInt(int parameterIndex, int x) throws SQLException {
    setIntImpl(parameterIndex, null, x);
  }

  protected void setLongImpl(int parameterIndex, String pgType, long x) throws SQLException {
    checkClosed();
    if (connection.binaryTransferSend(Oid.INT8)) {
      byte[] val = new byte[8];
      ByteConverter.int8(val, 0, x);
      bindBytes(parameterIndex, pgType, val, Oid.INT8);
      return;
    }
    bindLiteral(parameterIndex, pgType, -1, Long.toString(x), Oid.INT8);
  }

  @Override
  public final void setLong(int parameterIndex, long x) throws SQLException {
    setLongImpl(parameterIndex, null, x);
  }

  protected void setFloatImpl(int parameterIndex, String pgType, float x) throws SQLException {
    checkClosed();
    if (connection.binaryTransferSend(Oid.FLOAT4)) {
      byte[] val = new byte[4];
      ByteConverter.float4(val, 0, x);
      bindBytes(parameterIndex, pgType, val, Oid.FLOAT4);
      return;
    }
    bindLiteral(parameterIndex, pgType, -1, Float.toString(x), Oid.FLOAT8);
  }

  @Override
  public final void setFloat(int parameterIndex, float x) throws SQLException {
    setFloatImpl(parameterIndex, null, x);
  }

  protected void setDoubleImpl(int parameterIndex, String pgType, double x) throws SQLException {
    checkClosed();
    if (connection.binaryTransferSend(Oid.FLOAT8)) {
      byte[] val = new byte[8];
      ByteConverter.float8(val, 0, x);
      bindBytes(parameterIndex, pgType, val, Oid.FLOAT8);
      return;
    }
    bindLiteral(parameterIndex, pgType, -1, Double.toString(x), Oid.FLOAT8);
  }

  @Override
  public final void setDouble(int parameterIndex, double x) throws SQLException {
    setDoubleImpl(parameterIndex, null, x);
  }

  protected void setBigDecimalImpl(int parameterIndex, String pgType, int scale, BigDecimal x) throws SQLException {
    if (scale >= 0) {
      x = x.setScale(scale, RoundingMode.HALF_UP);
    }
    setNumberImpl(parameterIndex, pgType, scale, x);
  }

  @Override
  public final void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
    setBigDecimalImpl(parameterIndex, null, -1, x);
  }

  protected void setStringImpl(int parameterIndex, String pgType, String x) throws SQLException {
    checkClosed();
    setStringImpl(parameterIndex, pgType, x, getStringType());
  }

  @Override
  public final void setString(int parameterIndex, String x) throws SQLException {
    setStringImpl(parameterIndex, null, x);
  }

  private int getStringType() {
    return (connection.getStringVarcharFlag() ? Oid.VARCHAR : Oid.UNSPECIFIED);
  }

  /**
   * @param pgType the type name, if known, or {@code null} to use the default behavior
   */
  protected void setStringImpl(int parameterIndex, String pgType, String x, int oid) throws SQLException {
    // if the passed string is null, then set this column to null
    checkClosed();
    if (x == null) {
      preparedParameters.setNull(parameterIndex, pgType, -1, oid);
    } else {
      bindString(parameterIndex, x, oid, pgType);
    }
  }

  protected void setBytesImpl(int parameterIndex, String pgType, byte[] x) throws SQLException {
    checkClosed();

    if (null == x) {
      setNull(parameterIndex, Types.VARBINARY, pgType);
      return;
    }

    // Version 7.2 supports the bytea datatype for byte arrays
    byte[] copy = new byte[x.length];
    System.arraycopy(x, 0, copy, 0, x.length);
    // TODO: scale from length?
    preparedParameters.setBytea(parameterIndex, pgType, copy, 0, x.length);
  }

  @Override
  public final void setBytes(int parameterIndex, byte[] x) throws SQLException {
    setBytesImpl(parameterIndex, null, x);
  }

  protected void setDateImpl(int parameterIndex, String pgType, java.sql.Date x) throws SQLException {
    setDateImpl(parameterIndex, pgType, x, null);
  }

  @Override
  public final void setDate(int parameterIndex, java.sql.Date x) throws SQLException {
    setDateImpl(parameterIndex, null, x);
  }

  protected void setTimeImpl(int parameterIndex, String pgType, Time x) throws SQLException {
    setTimeImpl(parameterIndex, pgType, x, null);
  }

  @Override
  public final void setTime(int parameterIndex, Time x) throws SQLException {
    setTimeImpl(parameterIndex, null, x);
  }

  protected void setTimestampImpl(int parameterIndex, String pgType, Timestamp x) throws SQLException {
    setTimestampImpl(parameterIndex, pgType, x, null);
  }

  @Override
  public final void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
    setTimestampImpl(parameterIndex, null, x);
  }

  private void setCharacterStreamPost71(int parameterIndex, String pgType, InputStream x, int length,
      String encoding) throws SQLException {

    if (x == null) {
      setNull(parameterIndex, Types.VARCHAR, pgType);
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
      InputStreamReader l_inStream = new InputStreamReader(x, encoding);
      char[] l_chars = new char[length];
      int l_charsRead = 0;
      while (true) {
        int n = l_inStream.read(l_chars, l_charsRead, length - l_charsRead);
        if (n == -1) {
          break;
        }

        l_charsRead += n;

        if (l_charsRead == length) {
          break;
        }
      }

      setStringImpl(parameterIndex, pgType, new String(l_chars, 0, l_charsRead), Oid.VARCHAR);
    } catch (UnsupportedEncodingException l_uee) {
      throw new PSQLException(GT.tr("The JVM claims not to support the {0} encoding.", encoding),
          PSQLState.UNEXPECTED_ERROR, l_uee);
    } catch (IOException l_ioe) {
      throw new PSQLException(GT.tr("Provided InputStream failed."), PSQLState.UNEXPECTED_ERROR,
          l_ioe);
    }
  }

  protected void setAsciiStreamImpl(int parameterIndex, String pgType, InputStream x, int length) throws SQLException {
    checkClosed();
    setCharacterStreamPost71(parameterIndex, pgType, x, length, "ASCII");
  }

  @Override
  public final void setAsciiStream(int parameterIndex, InputStream x,
      int length) throws SQLException {
    setAsciiStreamImpl(parameterIndex, null, x, length);
  }

  protected void setUnicodeStreamImpl(int parameterIndex, String pgType, InputStream x,
      int length) throws SQLException {
    checkClosed();

    setCharacterStreamPost71(parameterIndex, pgType, x, length, "UTF-8");
  }

  @Override
  public final void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
    setUnicodeStreamImpl(parameterIndex, null, x, length);
  }

  protected void setBinaryStreamImpl(int parameterIndex, String pgType, InputStream x, int length) throws SQLException {
    checkClosed();

    if (x == null) {
      setNull(parameterIndex, Types.VARBINARY, pgType);
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
    preparedParameters.setBytea(parameterIndex, pgType, x, length);
  }

  @Override
  public final void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
    setBinaryStreamImpl(parameterIndex, null, x, length);
  }

  public void clearParameters() throws SQLException {
    preparedParameters.clear();
  }

  // Helper method for setting parameters to PGobject subclasses.
  private void setPGobjectImpl(int parameterIndex, String pgType, PGobject x) throws SQLException {
    String typename = x.getType();
    int oid = connection.getTypeInfo().getPGType(typename);
    if (oid == Oid.UNSPECIFIED) {
      throw new PSQLException(GT.tr("Unknown type {0}.", typename),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    // Maintain outermost pgType on nested objects
    if (pgType == null) {
      pgType = typename;
    }
    if ((x instanceof PGBinaryObject) && connection.binaryTransferSend(oid)) {
      PGBinaryObject binObj = (PGBinaryObject) x;
      byte[] data = new byte[binObj.lengthInBytes()];
      binObj.toBytes(data, 0);
      bindBytes(parameterIndex, pgType, data, oid);
    } else {
      setStringImpl(parameterIndex, pgType, x.getValue(), oid);
    }
  }

  private void setMapImpl(int parameterIndex, String pgType, Map<?, ?> x) throws SQLException {
    int oid = connection.getTypeInfo().getPGType("hstore");
    if (oid == Oid.UNSPECIFIED) {
      throw new PSQLException(GT.tr("No hstore extension installed."),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    // Maintain outermost pgType on nested objects
    if (pgType == null) {
      pgType = "hstore";
    }
    if (connection.binaryTransferSend(oid)) {
      byte[] data = HStoreConverter.toBytes(x, connection.getEncoding());
      bindBytes(parameterIndex, pgType, data, oid);
    } else {
      setStringImpl(parameterIndex, pgType, HStoreConverter.toString(x), oid);
    }
  }

  private void setNumberImpl(int parameterIndex, String pgType, int scale, Number x) throws SQLException {
    checkClosed();
    if (x == null) {
      setNullImpl(parameterIndex, pgType, scale, Types.DECIMAL);
    } else {
      bindLiteral(parameterIndex, pgType, scale, x.toString(), Oid.NUMERIC);
    }
  }

  /**
   * @see #setObject(int, java.lang.Object)
   * @see #setObject(int, java.lang.Object, int, int)
   *
   * @see ValueAccessHelper#getObject(org.postgresql.udt.ValueAccess, int, java.lang.String, java.lang.Class, org.postgresql.core.EnumMode, org.postgresql.udt.UdtMap, org.postgresql.util.PSQLState)
   */
  private boolean setEnumImpl(int parameterIndex, String pgType, Enum in, EnumMode enumMode) throws SQLException {
    // ValueAccessHelper.getObject is the inverse of this, and changes here will probably require changes there
    UdtMap udtMap = connection.getUdtMap();
    Class<? extends Enum> enumClass = in.getClass();
    Set<String> directTypes = udtMap.getInvertedDirect(enumClass);
    int size = directTypes.size();
    if (size == 1) {
      String inferredPgType = directTypes.iterator().next();
      if (LOGGER.isLoggable(Level.FINER)) {
        Class<? extends Enum> inferredClass = enumClass;
        LOGGER.log(Level.FINER,
            "  Found single match in direct inverted map, using as inferred type: {0} -> {1}",
            new Object[] {inferredPgType, inferredClass.getName()});
      }
      // Carry-through the type to avoid ::enumtype casting
      String name = in.name();
      int inferredOid = connection.getTypeInfo().getPGType(inferredPgType);
      if (LOGGER.isLoggable(Level.FINEST)) {
        LOGGER.log(Level.FINEST,
            "  enum direct: {0}, inferredPgType: {1}, inferredOid: {2}",
            new Object[] {name, inferredPgType, inferredOid});
      }
      // Maintain outermost pgType on nested objects
      if (pgType == null) {
        pgType = inferredPgType;
      }
      // TODO: Use getStringType() when getPGType returns Oid.UNSPECIFIED?
      setStringImpl(parameterIndex, pgType, name, inferredOid);
      return true;
    } else if (size > 1) {
      Set<String> sortedTypes = new TreeSet<String>(directTypes);
      LOGGER.log(Level.FINE, "  sortedTypes: {0}", sortedTypes);
      throw new PSQLException(GT.tr("Unable to infer type: more than one type directly maps to {0}: {1}",
          enumClass, sortedTypes.toString()),
              PSQLState.INVALID_PARAMETER_TYPE);
    } else {
      // Now check for inherited inference (matches the type or any subclass/implementation of it)
      Set<String> inheritedTypes = udtMap.getInvertedInherited(enumClass);
      size = inheritedTypes.size();
      if (size == 1) {
        String inferredPgType = inheritedTypes.iterator().next();
        // We've worked backward to a mapped pgType, now lookup which specific
        // class this pgType is mapped to:
        Class<?> inferredClassUnbounded = udtMap.getTypeMap().get(inferredPgType);
        if (LOGGER.isLoggable(Level.FINER)) {
          Class<? extends Enum> inferredClass = inferredClassUnbounded.asSubclass(enumClass);
          LOGGER.log(Level.FINER, "  Found single match in inherited inverted map, using as inferred type: {0} -> {1}",
              new Object[] {inferredPgType, inferredClass.getName()});
        }
        // Carry-through the type to avoid ::enumtype casting
        String name = in.name();
        int inferredOid = connection.getTypeInfo().getPGType(inferredPgType);
        if (LOGGER.isLoggable(Level.FINEST)) {
          LOGGER.log(Level.FINEST, "  enum inherited: {0}, inferredPgType: {1}, inferredOid: {2}",
              new Object[] {name, inferredPgType, inferredOid});
        }
        // Maintain outermost pgType on nested objects
        if (pgType == null) {
          pgType = inferredPgType;
        }
        // TODO: Use getStringType() when getPGType returns Oid.UNSPECIFIED?
        setStringImpl(parameterIndex, pgType, name, inferredOid);
        return true;
      } else if (size > 1) {
        // Sort types for easier reading
        Set<String> sortedTypes = new TreeSet<String>(inheritedTypes);
        LOGGER.log(Level.FINE, "  sortedTypes: {0}", sortedTypes);
        throw new PSQLException(GT.tr("Unable to infer type: more than one type maps to {0}: {1}",
            enumClass, sortedTypes.toString()),
                PSQLState.INVALID_PARAMETER_TYPE);
      } else {
        if (enumMode == EnumMode.ALWAYS) {
          // Still do when not in typemap, but without any ::enumtype casting
          String name = in.name();
          if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "  enum not mapped (enumMode={0}): {1}", new Object[] {enumMode, name});
          }
          setStringImpl(parameterIndex, pgType, name);
          return true;
        } else {
          return false;
        }
      }
    }
  }

  // TODO: InputStream and Reader: verify amount read against scaleOrLength per specifications
  protected void setObjectImpl(int parameterIndex, String pgType, int scaleOrLength, Object in, int targetSqlType)
      throws SQLException {
    checkClosed();

    if (in == null) {
      setNull(parameterIndex, targetSqlType, pgType);
      return;
    }

    boolean supportSQLData;
    EnumMode enumMode = connection.getEnumMode();
    if ((enumMode == EnumMode.ALWAYS || enumMode == EnumMode.TYPEMAP) && in instanceof Enum) {
      // TODO: targetSqlType == Types.OTHER only?
      if (setEnumImpl(parameterIndex, pgType, (Enum)in, enumMode)) {
        return;
      } else {
        // Enum type supported, but not in map, do not check for SQLData and fall-through to Object.toString().
        supportSQLData = false;
      }
    } else {
      // No Enum handling, support SQLData
      supportSQLData = true;
    }

    if (supportSQLData && in instanceof SQLData) {
      // TODO: targetSqlType == Types.OTHER only?
      // TODO: Should this be restricted to those types registered in the type map?
      // If so, can use connection.getTypeMapInvertedDirect(Class) for constant-time checks.
      // TODO: Carry-through the type to have ::sqldatatype instead of ::basetype, from SQLData.getSQLTypeName() then,
      //       when null, based on typemap
      SQLData sqlData = (SQLData)in;
      // Maintain outermost pgType on nested objects
      if (pgType == null) {
        pgType = sqlData.getSQLTypeName();
      }
      SingleAttributeSQLOutputHelper.writeSQLData(sqlData,
          new PgPreparedStatementSQLOutput(this, parameterIndex, pgType));
      return;
    }

    if (targetSqlType == Types.OTHER && in instanceof UUID
        && connection.haveMinimumServerVersion(ServerVersion.v8_3)) {
      setUuidImpl(parameterIndex, pgType, (UUID) in);
      return;
    }

    // TODO: Struct

    switch (targetSqlType) {
      case Types.SQLXML:
        if (in instanceof SQLXML) {
          setSQLXMLImpl(parameterIndex, pgType, (SQLXML) in);
        } else {
          setSQLXMLImpl(parameterIndex, pgType, new PgSQLXML(connection, in.toString()));
        }
        break;
      case Types.INTEGER:
        setIntImpl(parameterIndex, pgType, castToInt(in));
        break;
      case Types.TINYINT:
      case Types.SMALLINT:
        setShortImpl(parameterIndex, pgType, castToShort(in));
        break;
      case Types.BIGINT:
        setLongImpl(parameterIndex, pgType, castToLong(in));
        break;
      case Types.REAL:
        setFloatImpl(parameterIndex, pgType, castToFloat(in));
        break;
      case Types.DOUBLE:
      case Types.FLOAT:
        setDoubleImpl(parameterIndex, pgType, castToDouble(in));
        break;
      case Types.DECIMAL:
      case Types.NUMERIC:
        setBigDecimalImpl(parameterIndex, pgType, scaleOrLength, castToBigDecimal(in, scaleOrLength));
        break;
      case Types.CHAR:
        setStringImpl(parameterIndex, pgType, castToString(in), Oid.BPCHAR);
        break;
      case Types.VARCHAR:
        setStringImpl(parameterIndex, pgType, castToString(in), getStringType());
        break;
      case Types.LONGVARCHAR:
        if (in instanceof InputStream) {
          preparedParameters.setText(parameterIndex, pgType, scaleOrLength, (InputStream)in);
        } else {
          setStringImpl(parameterIndex, pgType, castToString(in), getStringType());
        }
        break;
      case Types.DATE:
        if (in instanceof java.sql.Date) {
          setDateImpl(parameterIndex, pgType, (java.sql.Date) in);
        } else {
          java.sql.Date tmpd;
          if (in instanceof java.util.Date) {
            tmpd = new java.sql.Date(((java.util.Date) in).getTime());
            //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
          } else if (in instanceof LocalDate) {
            setDateImpl(parameterIndex, pgType, (LocalDate) in);
            break;
            //#endif
          } else {
            tmpd = connection.getTimestampUtils().toDate(getDefaultCalendar(), in.toString());
          }
          setDateImpl(parameterIndex, pgType, tmpd);
        }
        break;
      case Types.TIME:
        if (in instanceof java.sql.Time) {
          setTimeImpl(parameterIndex, pgType, (java.sql.Time) in);
        } else {
          java.sql.Time tmpt;
          if (in instanceof java.util.Date) {
            tmpt = new java.sql.Time(((java.util.Date) in).getTime());
            //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
          } else if (in instanceof LocalTime) {
            setTimeImpl(parameterIndex, pgType, (LocalTime) in);
            break;
            //#endif
          } else {
            tmpt = connection.getTimestampUtils().toTime(getDefaultCalendar(), in.toString());
          }
          setTimeImpl(parameterIndex, pgType, tmpt);
        }
        break;
      case Types.TIMESTAMP:
        if (in instanceof PGTimestamp) {
          setObjectImpl(parameterIndex, pgType, -1, in);
        } else if (in instanceof java.sql.Timestamp) {
          setTimestampImpl(parameterIndex, pgType, (java.sql.Timestamp) in);
        } else {
          java.sql.Timestamp tmpts;
          if (in instanceof java.util.Date) {
            tmpts = new java.sql.Timestamp(((java.util.Date) in).getTime());
            //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
          } else if (in instanceof LocalDateTime) {
            setTimestampImpl(parameterIndex, pgType, (LocalDateTime) in);
            break;
            //#endif
          } else {
            tmpts = connection.getTimestampUtils().toTimestamp(getDefaultCalendar(), in.toString());
          }
          setTimestampImpl(parameterIndex, pgType, tmpts);
        }
        break;
      //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
      case Types.TIMESTAMP_WITH_TIMEZONE:
        if (in instanceof OffsetDateTime) {
          setTimestampImpl(parameterIndex, pgType, (OffsetDateTime) in);
        } else if (in instanceof PGTimestamp) {
          setObjectImpl(parameterIndex, pgType, -1, in);
        } else {
          throw new PSQLException(
              GT.tr("Cannot cast an instance of {0} to type {1}",
                  in.getClass().getName(), "Types.TIMESTAMP_WITH_TIMEZONE"),
              PSQLState.INVALID_PARAMETER_TYPE);
        }
        break;
      //#endif
      case Types.BOOLEAN:
      case Types.BIT:
        setBooleanImpl(parameterIndex, pgType, BooleanTypeUtil.castToBoolean(in));
        break;
      case Types.BINARY:
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
        setObjectImpl(parameterIndex, pgType, scaleOrLength, in);
        break;
      case Types.BLOB:
        if (in instanceof Blob) {
          setBlobImpl(parameterIndex, pgType, (Blob) in);
        } else if (in instanceof InputStream) {
          // TODO: Verify scaleOrLength
          long oid = createBlob((InputStream) in, -1);
          setLongImpl(parameterIndex, pgType, oid);
        } else {
          throw new PSQLException(
              GT.tr("Cannot cast an instance of {0} to type {1}",
                  in.getClass().getName(), "Types.BLOB"),
              PSQLState.INVALID_PARAMETER_TYPE);
        }
        break;
      case Types.CLOB:
        if (in instanceof Clob) {
          setClobImpl(parameterIndex, pgType, (Clob) in);
        } else {
          throw new PSQLException(
              GT.tr("Cannot cast an instance of {0} to type {1}",
                  in.getClass().getName(), "Types.CLOB"),
              PSQLState.INVALID_PARAMETER_TYPE);
        }
        break;
      case Types.ARRAY:
        if (in instanceof Array) {
          setArrayImpl(parameterIndex, pgType, (Array) in);
        } else if (PrimitiveArraySupport.isSupportedPrimitiveArray(in)) {
          setPrimitiveArrayImpl(parameterIndex, pgType, in);
        } else {
          throw new PSQLException(
              GT.tr("Cannot cast an instance of {0} to type {1}",
                  in.getClass().getName(), "Types.ARRAY"),
              PSQLState.INVALID_PARAMETER_TYPE);
        }
        break;
      case Types.DISTINCT:
        bindString(parameterIndex, in.toString(), Oid.UNSPECIFIED, pgType);
        break;
      case Types.OTHER:
        if (in instanceof PGobject) {
          setPGobjectImpl(parameterIndex, pgType, (PGobject) in);
        } else if (in instanceof Map) {
          setMapImpl(parameterIndex, pgType, (Map<?, ?>) in);
        } else {
          bindString(parameterIndex, in.toString(), Oid.UNSPECIFIED, pgType);
        }
        break;
      default:
        throw new PSQLException(GT.tr("Unsupported Types value: {0}", targetSqlType),
            PSQLState.INVALID_PARAMETER_TYPE);
    }
  }

  @Override
  public final void setObject(int parameterIndex, Object in, int targetSqlType, int scaleOrLength)
      throws SQLException {
    setObjectImpl(parameterIndex, null, scaleOrLength, in, targetSqlType);
  }

  private <A> void setPrimitiveArrayImpl(int parameterIndex, String pgType, A in) throws SQLException {
    final PrimitiveArraySupport<A> arrayToString = PrimitiveArraySupport.getArraySupport(in);

    final TypeInfo typeInfo = connection.getTypeInfo();

    final int oid = arrayToString.getDefaultArrayTypeOid(typeInfo);

    if (arrayToString.supportBinaryRepresentation() && connection.getPreferQueryMode() != PreferQueryMode.SIMPLE) {
      bindBytes(parameterIndex, pgType, arrayToString.toBinaryRepresentation(connection, in), oid);
    } else {
      final char delim = typeInfo.getArrayDelimiter(oid);
      setStringImpl(parameterIndex, pgType, arrayToString.toArrayString(delim, in), oid);
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
      final Exception cause) {
    return new PSQLException(
        GT.tr("Cannot convert an instance of {0} to type {1}", fromType, toType),
        PSQLState.INVALID_PARAMETER_TYPE, cause);
  }

  /**
   * TODO: The specification states "except that it assumes a scale of zero" in
   * {@link PreparedStatement#setObject(int, java.lang.Object, int)}.  Passing {@code -1} here in violation of the
   * specification.  Is this an intentional deviation from the specification?
   * <p>
   * If so, maybe we need a {@link PGProperty} that toggles a strict JDBC-compliant mode, which would change this.  This
   * toggle could then also be used for things like {@link InputStream} and {@link Reader} length validation on
   * parameters, which is currently not done), unless we're willing to break things for the sake of standards
   * compliance.
   * </p>
   * <p>
   * When changed to {@code 0}, {@link Types#NUMERIC} values are then scaled to {@code 0}, which breaks a few of the
   * existing tests.
   * </p>
   *
   * @see PreparedStatement#setObject(int, java.lang.Object, int)
   */
  protected void setObjectImpl(int parameterIndex, String pgType, Object x, int targetSqlType) throws SQLException {
    setObjectImpl(parameterIndex, pgType, -1, // TODO: Should this be 0?
        x, targetSqlType);
  }

  @Override
  public final void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
    setObjectImpl(parameterIndex, null, x, targetSqlType);
  }

  /*
   * This stores an Object into a parameter.
   */
  // TODO: The specification states, but this is not enforced here - or other setObject methods within this project:
  //       <b>Note:</b> This method throws an exception if there is an ambiguity, for example, if the
  //       object is of a class implementing more than one of the interfaces named above.

  // TODO: InputStream? (verify scaleOrLength)
  // TODO: Reader? (verify scaleOrLength)
  // scaleOrLength unused?
  protected void setObjectImpl(int parameterIndex, String pgType, int scaleOrLength, Object x) throws SQLException {
    checkClosed();

    if (x == null) {
      setNull(parameterIndex, Types.OTHER, pgType);
      return;
    }

    boolean supportSQLData;
    EnumMode enumMode = connection.getEnumMode();
    if ((enumMode == EnumMode.ALWAYS || enumMode == EnumMode.TYPEMAP) && x instanceof Enum) {
      if (setEnumImpl(parameterIndex, pgType, (Enum)x, enumMode)) {
        return;
      } else {
        // Enum type supported, but not in map, do not check for SQLData and fall-through to Object.toString().
        supportSQLData = false;
      }
    } else {
      // No Enum handling, support SQLData
      supportSQLData = true;
    }

    // TODO: Struct

    if (supportSQLData && x instanceof SQLData) {
      // TODO: Should this be restricted to those types registered in the type map?
      // If so, can use connection.getTypeMapInvertedDirect(Class) for constant-time checks.
      SingleAttributeSQLOutputHelper.writeSQLData((SQLData)x, new PgPreparedStatementSQLOutput(this, parameterIndex, pgType));
    } else if (x instanceof UUID && connection.haveMinimumServerVersion(ServerVersion.v8_3)) {
      setUuidImpl(parameterIndex, pgType, (UUID) x);
    } else if (x instanceof SQLXML) {
      setSQLXMLImpl(parameterIndex, pgType, (SQLXML) x);
    } else if (x instanceof String) {
      setStringImpl(parameterIndex, pgType, (String) x);
    } else if (x instanceof BigDecimal) {
      setBigDecimalImpl(parameterIndex, pgType, scaleOrLength, (BigDecimal) x);
    } else if (x instanceof Short) {
      setShortImpl(parameterIndex, pgType, (Short) x);
    } else if (x instanceof Integer) {
      setIntImpl(parameterIndex, pgType, (Integer) x);
    } else if (x instanceof Long) {
      setLongImpl(parameterIndex, pgType, (Long) x);
    } else if (x instanceof Float) {
      setFloatImpl(parameterIndex, pgType, (Float) x);
    } else if (x instanceof Double) {
      setDoubleImpl(parameterIndex, pgType, (Double) x);
    } else if (x instanceof byte[]) {
      setBytesImpl(parameterIndex, pgType, (byte[]) x);
    } else if (x instanceof java.sql.Date) {
      setDateImpl(parameterIndex, pgType, (java.sql.Date) x);
    } else if (x instanceof Time) {
      setTimeImpl(parameterIndex, pgType, (Time) x);
    } else if (x instanceof Timestamp) {
      setTimestampImpl(parameterIndex, pgType, (Timestamp) x);
    } else if (x instanceof Boolean) {
      setBooleanImpl(parameterIndex, pgType, (Boolean) x);
    } else if (x instanceof Byte) {
      setByteImpl(parameterIndex, pgType, (Byte) x);
    } else if (x instanceof Blob) {
      setBlobImpl(parameterIndex, pgType, (Blob) x);
    } else if (x instanceof Clob) {
      setClobImpl(parameterIndex, pgType, (Clob) x);
    } else if (x instanceof Array) {
      setArrayImpl(parameterIndex, pgType, (Array) x);
    } else if (x instanceof PGobject) {
      setPGobjectImpl(parameterIndex, pgType, (PGobject) x);
    } else if (x instanceof Character) {
      setStringImpl(parameterIndex, pgType, ((Character) x).toString());
      //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
    } else if (x instanceof LocalDate) {
      setDateImpl(parameterIndex, pgType, (LocalDate) x);
    } else if (x instanceof LocalTime) {
      setTimeImpl(parameterIndex, pgType, (LocalTime) x);
    } else if (x instanceof LocalDateTime) {
      setTimestampImpl(parameterIndex, pgType, (LocalDateTime) x);
    } else if (x instanceof OffsetDateTime) {
      setTimestampImpl(parameterIndex, pgType, (OffsetDateTime) x);
      //#endif
    } else if (x instanceof Map) {
      setMapImpl(parameterIndex, pgType, (Map<?, ?>) x);
    } else if (x instanceof Number) {
      // TODO: Pass scale through to all Number, nos just BigDecimal?
      setNumberImpl(parameterIndex, pgType, scaleOrLength, (Number) x);
    } else if (PrimitiveArraySupport.isSupportedPrimitiveArray(x)) {
      setPrimitiveArrayImpl(parameterIndex, pgType, x);
    } else {
      // Can't infer a type.
      throw new PSQLException(GT.tr(
          "Can''t infer the SQL type to use for an instance of {0}. Use setObject() with an explicit Types value to specify the type to use.",
          x.getClass().getName()), PSQLState.INVALID_PARAMETER_TYPE);
    }
  }

  @Override
  public final void setObject(int parameterIndex, Object x) throws SQLException {
    setObjectImpl(parameterIndex, null, -1, x);
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
   * @param parameterIndex parameter index
   * @param pgType the type name, if known, or {@code null} to use the default behavior
   * @param s value (the value should already be escaped)
   * @param oid type oid
   * @throws SQLException if something goes wrong
   */
  protected void bindLiteral(int parameterIndex, String pgType, int scale, String s, int oid) throws SQLException {
    preparedParameters.setLiteralParameter(parameterIndex, pgType, scale, s, oid);
  }

  protected void bindBytes(int parameterIndex, String pgType, byte[] b, int oid) throws SQLException {
    preparedParameters.setBinaryParameter(parameterIndex, pgType, b, oid);
  }

  /**
   * This version is for values that should turn into strings e.g. setString directly calls
   * bindString with no escaping; the per-protocol ParameterList does escaping as needed.
   *
   * @param parameterIndex parameter index
   * @param s value
   * @param oid type oid
   * @param pgType the type name, if known, or {@code null} to use the default behavior
   * @throws SQLException if something goes wrong
   */
  private void bindString(int parameterIndex, String s, int oid, String pgType) throws SQLException {
    preparedParameters.setStringParameter(parameterIndex, pgType, -1, s, oid);
  }

  public boolean isUseServerPrepare() {
    return (preparedQuery != null && m_prepareThreshold != 0
        && preparedQuery.getExecuteCount() + 1 >= m_prepareThreshold);
  }

  public void addBatch(String p_sql) throws SQLException {
    checkClosed();

    throw new PSQLException(
        GT.tr("Can''t use query methods that take a query string on a PreparedStatement."),
        PSQLState.WRONG_OBJECT_TYPE);
  }

  public void addBatch() throws SQLException {
    checkClosed();
    if (batchStatements == null) {
      batchStatements = new ArrayList<Query>();
      batchParameters = new ArrayList<ParameterList>();
    }
    // we need to create copies of our parameters, otherwise the values can be changed
    batchParameters.add(preparedParameters.copy());
    Query query = preparedQuery.query;
    if (!(query instanceof BatchedQuery) || batchStatements.isEmpty()) {
      batchStatements.add(query);
    }
  }

  public ResultSetMetaData getMetaData() throws SQLException {
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

  protected void setArrayImpl(int parameterIndex, String pgType, java.sql.Array x) throws SQLException {
    checkClosed();

    if (null == x) {
      setNull(parameterIndex, Types.ARRAY, pgType);
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
      if (arr.isBinary()) {
        bindBytes(parameterIndex, pgType, arr.toBytes(), oid);
        return;
      }
    }

    setStringImpl(parameterIndex, pgType, x.toString(), oid);
  }

  @Override
  public final void setArray(int parameterIndex, java.sql.Array x) throws SQLException {
    setArrayImpl(parameterIndex, null, x);
  }

  protected long createBlob(InputStream inputStream, long length) throws SQLException {
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

  protected void setBlobImpl(int parameterIndex, String pgType, Blob x) throws SQLException {
    checkClosed();

    if (x == null) {
      setNullImpl(parameterIndex, pgType, -1, Types.BLOB);
      return;
    }

    InputStream inStream = x.getBinaryStream();
    try {
      long oid = createBlob(inStream, x.length());
      setLongImpl(parameterIndex, pgType, oid);
    } finally {
      try {
        inStream.close();
      } catch (Exception e) {
      }
    }
  }

  @Override
  public final void setBlob(int parameterIndex, Blob x) throws SQLException {
    setBlobImpl(parameterIndex, null, x);
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

  protected void setCharacterStreamImpl(int parameterIndex, String pgType, java.io.Reader x,
      int length) throws SQLException {
    checkClosed();

    if (x == null) {
      setNull(parameterIndex, Types.VARCHAR, pgType);
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
    setStringImpl(parameterIndex, pgType, readerToString(x, length));
  }

  @Override
  public final void setCharacterStream(int parameterIndex, java.io.Reader x, int length) throws SQLException {
    setCharacterStreamImpl(parameterIndex, null, x, length);
  }

  protected void setClobImpl(int parameterIndex, String pgType, Clob x) throws SQLException {
    checkClosed();

    if (x == null) {
      setNull(parameterIndex, Types.CLOB, pgType);
      return;
    }

    Reader l_inStream = x.getCharacterStream();
    int l_length = (int) x.length();
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
      int c = l_inStream.read();
      int p = 0;
      while (c > -1 && p < l_length) {
        lw.write(c);
        c = l_inStream.read();
        p++;
      }
      lw.close();
    } catch (IOException se) {
      throw new PSQLException(GT.tr("Unexpected error writing large object to database."),
          PSQLState.UNEXPECTED_ERROR, se);
    }
    // lob is closed by the stream so don't call lob.close()
    setLongImpl(parameterIndex, pgType, oid);
  }

  @Override
  public final void setClob(int parameterIndex, Clob x) throws SQLException {
    setClobImpl(parameterIndex, null, x);
  }

  @Override
  public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
    if (typeName == null) {
      setNull(parameterIndex, sqlType);
      return;
    }

    checkClosed();

    TypeInfo typeInfo = connection.getTypeInfo();
    int oid = typeInfo.getPGType(typeName);

    preparedParameters.setNull(parameterIndex, typeName, -1, oid);
  }

  protected void setRefImpl(int parameterIndex, String pgType, Ref x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setRefImpl(int,String,Ref)");
  }

  @Override
  public final void setRef(int parameterIndex, Ref x) throws SQLException {
    setRefImpl(parameterIndex, null, x);
  }

  protected void setDateImpl(int parameterIndex, String pgType, java.sql.Date d,
      java.util.Calendar cal) throws SQLException {
    checkClosed();

    if (d == null) {
      setNull(parameterIndex, Types.DATE, pgType);
      return;
    }

    if (connection.binaryTransferSend(Oid.DATE)) {
      byte[] val = new byte[4];
      TimeZone tz = cal != null ? cal.getTimeZone() : null;
      connection.getTimestampUtils().toBinDate(tz, val, d);
      preparedParameters.setBinaryParameter(parameterIndex, pgType, val, Oid.DATE);
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
    bindString(parameterIndex, connection.getTimestampUtils().toString(cal, d), Oid.UNSPECIFIED, pgType);
  }

  @Override
  public final void setDate(int parameterIndex, java.sql.Date d, java.util.Calendar cal) throws SQLException {
    setDateImpl(parameterIndex, null, d, cal);
  }

  protected void setTimeImpl(int parameterIndex, String pgType, Time t, java.util.Calendar cal) throws SQLException {
    checkClosed();

    if (t == null) {
      setNull(parameterIndex, Types.TIME, pgType);
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
    bindString(parameterIndex, connection.getTimestampUtils().toString(cal, t), oid, pgType);
  }

  @Override
  public final void setTime(int parameterIndex, Time t, java.util.Calendar cal) throws SQLException {
    setTimeImpl(parameterIndex, null, t, cal);
  }

  protected void setTimestampImpl(int parameterIndex, String pgType, Timestamp t,
      java.util.Calendar cal) throws SQLException {
    checkClosed();

    if (t == null) {
      setNull(parameterIndex, Types.TIMESTAMP, pgType);
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
    bindString(parameterIndex, connection.getTimestampUtils().toString(cal, t), oid, pgType);
  }

  @Override
  public final void setTimestamp(int parameterIndex, Timestamp t, java.util.Calendar cal) throws SQLException {
    setTimestampImpl(parameterIndex, null, t, cal);
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  private void setDateImpl(int parameterIndex, String pgType, LocalDate localDate) throws SQLException {
    int oid = Oid.DATE;
    bindString(parameterIndex, connection.getTimestampUtils().toString(localDate), oid, pgType);
  }

  private void setTimeImpl(int parameterIndex, String pgType, LocalTime localTime) throws SQLException {
    int oid = Oid.TIME;
    bindString(parameterIndex, connection.getTimestampUtils().toString(localTime), oid, pgType);
  }

  private void setTimestampImpl(int parameterIndex, String pgType, LocalDateTime localDateTime) throws SQLException {
    int oid = Oid.TIMESTAMP;
    bindString(parameterIndex, connection.getTimestampUtils().toString(localDateTime), oid, pgType);
  }

  private void setTimestampImpl(int parameterIndex, String pgType, OffsetDateTime offsetDateTime) throws SQLException {
    int oid = Oid.TIMESTAMPTZ;
    bindString(parameterIndex, connection.getTimestampUtils().toString(offsetDateTime), oid, pgType);
  }
  //#endif

  public ParameterMetaData createParameterMetaData(BaseConnection conn, int[] oids, String[] pgTypes, int[] scales)
      throws SQLException {
    return new PgParameterMetaData(conn, oids, pgTypes, scales);
  }


  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  protected void setObjectImpl(int parameterIndex, String pgType, int scaleOrLength, Object x,
      java.sql.SQLType targetSqlType) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObjectImpl(int, String, Object, SQLType, int)");
  }

  @Override
  public final void setObject(int parameterIndex, Object x, java.sql.SQLType targetSqlType,
      int scaleOrLength) throws SQLException {
    setObjectImpl(parameterIndex, null, scaleOrLength, x, targetSqlType);
  }

  protected void setObjectImpl(int parameterIndex, String pgType, Object x, java.sql.SQLType targetSqlType)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setObjectImpl(int, String, Object, SQLType)");
  }

  @Override
  public final void setObject(int parameterIndex, Object x, java.sql.SQLType targetSqlType)
      throws SQLException {
    setObjectImpl(parameterIndex, null, x, targetSqlType);
  }
  //#endif


  protected void setRowIdImpl(int parameterIndex, String pgType, RowId x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setRowIdImpl(int, String, RowId)");
  }

  @Override
  public final void setRowId(int parameterIndex, RowId x) throws SQLException {
    setRowIdImpl(parameterIndex, null, x);
  }

  protected void setNStringImpl(int parameterIndex, String pgType, String value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNStringImpl(int, String, String)");
  }

  @Override
  public final void setNString(int parameterIndex, String value) throws SQLException {
    setNStringImpl(parameterIndex, null, value);
  }

  protected void setNCharacterStreamImpl(int parameterIndex, String pgType, Reader value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNCharacterStreamImpl(int, String, Reader, long)");
  }

  @Override
  public final void setNCharacterStream(int parameterIndex, Reader value, long length)
      throws SQLException {
    setNCharacterStreamImpl(parameterIndex, null, value, length);
  }

  protected void setNCharacterStreamImpl(int parameterIndex, String pgType, Reader value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNCharacterStreamImpl(int, String, Reader)");
  }

  @Override
  public final void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
    setNCharacterStreamImpl(parameterIndex, null, value);
  }

  protected void setCharacterStreamImpl(int parameterIndex, String pgType, Reader value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setCharacterStreamImpl(int, String, Reader, long)");
  }

  @Override
  public final void setCharacterStream(int parameterIndex, Reader value, long length)
      throws SQLException {
    setCharacterStreamImpl(parameterIndex, null, value, length);
  }

  protected void setCharacterStreamImpl(int parameterIndex, String pgType, Reader value) throws SQLException {
    if (connection.getPreferQueryMode() == PreferQueryMode.SIMPLE) {
      String s = (value != null) ? readerToString(value, Integer.MAX_VALUE) : null;
      setStringImpl(parameterIndex, pgType, s);
      return;
    }
    InputStream is = (value != null) ? new ReaderInputStream(value) : null;
    setObjectImpl(parameterIndex, pgType, is, Types.LONGVARCHAR);
  }

  @Override
  public final void setCharacterStream(int parameterIndex, Reader value) throws SQLException {
    setCharacterStreamImpl(parameterIndex, null, value);
  }

  protected void setBinaryStreamImpl(int parameterIndex, String pgType, InputStream value, long length)
      throws SQLException {
    if (length > Integer.MAX_VALUE) {
      throw new PSQLException(GT.tr("Object is too large to send over the protocol."),
          PSQLState.NUMERIC_CONSTANT_OUT_OF_RANGE);
    }
    preparedParameters.setBytea(parameterIndex, pgType, value, (int) length);
  }

  @Override
  public final void setBinaryStream(int parameterIndex, InputStream value, long length)
      throws SQLException {
    setBinaryStreamImpl(parameterIndex, null, value, length);
  }

  protected void setBinaryStreamImpl(int parameterIndex, String pgType, InputStream value) throws SQLException {
    preparedParameters.setBytea(parameterIndex, pgType, -1, value);
  }

  @Override
  public final void setBinaryStream(int parameterIndex, InputStream value) throws SQLException {
    setBinaryStreamImpl(parameterIndex, null, value);
  }

  protected void setAsciiStreamImpl(int parameterIndex, String pgType, InputStream value, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setAsciiStreamImpl(int, String, InputStream, long)");
  }

  @Override
  public final void setAsciiStream(int parameterIndex, InputStream value, long length)
      throws SQLException {
    setAsciiStreamImpl(parameterIndex, null, value, length);
  }

  protected void setAsciiStreamImpl(int parameterIndex, String pgType, InputStream value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setAsciiStream(int, String, InputStream)");
  }

  @Override
  public final void setAsciiStream(int parameterIndex, InputStream value) throws SQLException {
    setAsciiStreamImpl(parameterIndex, null, value);
  }

  protected void setNClobImpl(int parameterIndex, String pgType, NClob value) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClobImpl(int, String, NClob)");
  }

  @Override
  public final void setNClob(int parameterIndex, NClob value) throws SQLException {
    setNClobImpl(parameterIndex, null, value);
  }

  protected void setClobImpl(int parameterIndex, String pgType, Reader reader, long length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setClobImpl(int, String, Reader, long)");
  }

  @Override
  public final void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
    setClobImpl(parameterIndex, null, reader, length);
  }

  protected void setClobImpl(int parameterIndex, String pgType, Reader reader) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setClobImpl(int, String, Reader)");
  }

  @Override
  public final void setClob(int parameterIndex, Reader reader) throws SQLException {
    setClobImpl(parameterIndex, null, reader);
  }

  protected void setBlobImpl(int parameterIndex, String pgType, InputStream inputStream, long length)
      throws SQLException {
    checkClosed();

    if (inputStream == null) {
      setNull(parameterIndex, Types.BLOB, pgType);
      return;
    }

    if (length < 0) {
      throw new PSQLException(GT.tr("Invalid stream length {0}.", length),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    long oid = createBlob(inputStream, length);
    setLongImpl(parameterIndex, pgType, oid);
  }

  @Override
  public final void setBlob(int parameterIndex, InputStream inputStream, long length)
      throws SQLException {
    setBlobImpl(parameterIndex, null, inputStream, length);
  }

  protected void setBlobImpl(int parameterIndex, String pgType, InputStream inputStream) throws SQLException {
    checkClosed();

    if (inputStream == null) {
      setNull(parameterIndex, Types.BLOB, pgType);
      return;
    }

    long oid = createBlob(inputStream, -1);
    setLongImpl(parameterIndex, pgType, oid);
  }

  @Override
  public final void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
    setBlobImpl(parameterIndex, null, inputStream);
  }

  protected void setNClobImpl(int parameterIndex, String pgType, Reader reader, long length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClobImpl(int, String, Reader, long)");
  }

  @Override
  public final void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
    setNClobImpl(parameterIndex, null, reader, length);
  }

  protected void setNClobImpl(int parameterIndex, String pgType, Reader reader) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setNClob(int, String, Reader)");
  }

  @Override
  public final void setNClob(int parameterIndex, Reader reader) throws SQLException {
    setNClobImpl(parameterIndex, null, reader);
  }

  protected void setSQLXMLImpl(int parameterIndex, String pgType, SQLXML xmlObject) throws SQLException {
    checkClosed();
    String stringValue = xmlObject == null ? null : xmlObject.getString();
    if (stringValue == null) {
      setNull(parameterIndex, Types.SQLXML, pgType);
    } else {
      setStringImpl(parameterIndex, pgType, stringValue, Oid.XML);
    }
  }

  @Override
  public final void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
    setSQLXMLImpl(parameterIndex, null, xmlObject);
  }

  private void setUuidImpl(int parameterIndex, String pgType, UUID uuid) throws SQLException {
    if (connection.binaryTransferSend(Oid.UUID)) {
      byte[] val = new byte[16];
      ByteConverter.int8(val, 0, uuid.getMostSignificantBits());
      ByteConverter.int8(val, 8, uuid.getLeastSignificantBits());
      bindBytes(parameterIndex, pgType, val, Oid.UUID);
    } else {
      bindLiteral(parameterIndex, pgType, -1, uuid.toString(), Oid.UUID);
    }
  }

  protected void setURLImpl(int parameterIndex, String pgType, java.net.URL url) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setURLImpl(int, String, URL)");
  }

  @Override
  public final void setURL(int parameterIndex, java.net.URL url) throws SQLException {
    setURLImpl(parameterIndex, null, url);
  }

  @Override
  public int[] executeBatch() throws SQLException {
    try {
      // Note: in batch prepared statements batchStatements == 1, and batchParameters is equal
      // to the number of addBatch calls
      // batchParameters might be empty in case of empty batch
      if (batchParameters != null && batchParameters.size() > 1 && m_prepareThreshold > 0) {
        // Use server-prepared statements when there's more than one statement in a batch
        // Technically speaking, it might cause to create a server-prepared statement
        // just for 2 executions even for prepareThreshold=5. That however should be
        // acceptable since prepareThreshold is a optimization kind of parameter.
        this.preparedQuery.increaseExecuteCount(m_prepareThreshold);
      }
      return super.executeBatch();
    } finally {
      defaultTimeZone = null;
    }
  }

  private Calendar getDefaultCalendar() {
    TimestampUtils timestampUtils = connection.getTimestampUtils();
    if (timestampUtils.hasFastDefaultTimeZone()) {
      return timestampUtils.getSharedCalendar(null);
    }
    Calendar sharedCalendar = timestampUtils.getSharedCalendar(defaultTimeZone);
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
    if (oids != null) {
      return createParameterMetaData(connection, oids, preparedParameters.getPgTypes(), preparedParameters.getScales());
    }

    return null;

  }

  @Override
  protected void transformQueriesAndParameters() throws SQLException {
    if (batchParameters.size() <= 1
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
    ArrayList<ParameterList> newBatchParameters = new ArrayList<ParameterList>(count);
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
        newPl.appendAll(pl);
      }
      newBatchStatements.add(bq);
      newBatchParameters.add(newPl);
      unprocessedBatchCount -= valueBlock;
    }
    batchStatements = newBatchStatements;
    batchParameters = newBatchParameters;
  }
}
