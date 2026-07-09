/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.Driver;
import org.postgresql.PGRefCursorResultSet;
import org.postgresql.PGResultSetMetaData;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveDecoders;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.Encoding;
import org.postgresql.core.Field;
import org.postgresql.core.Oid;
import org.postgresql.core.Query;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.ResultHandlerBase;
import org.postgresql.core.TransactionState;
import org.postgresql.core.Tuple;
import org.postgresql.core.TypeInfo;
import org.postgresql.core.Utils;
import org.postgresql.jdbc.codec.CompositeCodec;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.JdbcBlackHole;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGbytea;
import org.postgresql.util.PGobject;
import org.postgresql.util.PGtokenizer;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.index.qual.Positive;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.checkerframework.dataflow.qual.Pure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringTokenizer;
import java.util.UUID;
import java.util.logging.Level;

public class PgResultSet implements ResultSet, PGRefCursorResultSet {

  // needed for updateable result set support
  private boolean updateable;
  private boolean doingUpdates;
  private @Nullable HashMap<String, @Nullable Object> updateValues;
  // target SQLType for a subset of updateValues, keyed the same way; populated only by the
  // updateObject(..., SQLType, ...) overloads, and consulted at bind time in insertRow/updateRow
  // so a caller-supplied PostgreSQL type (e.g. PGSQLType.XID8) routes through the codec registry
  // exactly like PgPreparedStatement#setObject(int, Object, SQLType, int) does.
  private @Nullable HashMap<String, SQLType> updateSqlTypes;
  private boolean usingOID; // are we using the OID for the primary key?
  private @Nullable List<PrimaryKey> primaryKeys; // list of primary keys
  private boolean singleTable;
  private String onlyTable = "";
  private @Nullable String tableName;
  private @Nullable PreparedStatement deleteStatement;
  private final int resultsettype;
  private final int resultsetconcurrency;
  private int fetchdirection = ResultSet.FETCH_UNKNOWN;
  private final DateTimeHelper dateTimeHelper;
  protected final BaseConnection connection; // the connection we belong to
  protected final BaseStatement statement; // the statement we belong to
  protected final Field[] fields; // Field metadata for this resultset.
  protected final @Nullable Query originalQuery; // Query we originated from

  protected final int maxRows; // Maximum rows in this resultset (might be 0).
  protected final int maxFieldSize; // Maximum field size in this resultset (might be 0).

  protected @Nullable List<Tuple> rows; // Current page of results.
  protected int currentRow = -1; // Index into 'rows' of our current row (0-based)
  protected int rowOffset; // Offset of row 0 in the actual resultset
  protected @Nullable Tuple thisRow; // copy of the current result row
  protected @Nullable SQLWarning warnings; // The warning chain
  /**
   * True if the last obtained column value was SQL NULL as specified by {@link #wasNull}. The value
   * is always updated by the {@link #getRawValue} method.
   */
  protected boolean wasNullFlag;
  protected boolean onInsertRow;
  // are we on the insert row (for JDBC2 updatable resultsets)?

  private @Nullable Tuple rowBuffer; // updateable rowbuffer

  protected int fetchSize; // Current fetch size (might be 0).
  protected int lastUsedFetchSize; // Fetch size used during last fetch
  protected boolean adaptiveFetch;
  protected @Nullable ResultCursor cursor; // Cursor for fetching additional data.

  // Speed up findColumn by caching lookups
  private @Nullable Map<String, Integer> columnNameIndexMap;

  private @Nullable ResultSetMetaData rsMetaData;
  private final ResourceLock lock = new ResourceLock();

  // Codec support
  private @Nullable PgCodecContext codecContext;

  /**
   * Gets the codec context for this result set.
   * The context is lazily initialized on first access.
   *
   * @return the codec context
   * @throws SQLException if the context cannot be created
   */
  protected PgCodecContext getCodecContext() throws SQLException {
    PgCodecContext ctx = codecContext;
    if (ctx == null) {
      // Defer to the connection so the codec context picks up the current
      // typeMap and java.time / convertBooleanToNumeric preferences. Then
      // bind the per-resultset TimestampUtils so timezone caching is scoped
      // to this ResultSet (TimezoneCachingTest's contract: each codec call
      // populates the result set's own dateTimeHelper.defaultTimeZone).
      ctx = connection.getCodecContext()
          .withTimestampUtils(dateTimeHelper.getTimestampUtils());
      codecContext = ctx;
    }
    return ctx;
  }

  /**
   * Overrides the type map used by this ResultSet's getters. Used by
   * {@link PgArray#getResultSet(java.util.Map)} so that composite array
   * elements are decoded through the caller-supplied {@code SQLData} mapping
   * when {@code getObject} is called on the returned rows. The connection's
   * java.time / boolean preferences and the per-ResultSet TimestampUtils are
   * preserved.
   *
   * @param typeMap the type map to apply for this ResultSet
   * @throws SQLException if the context cannot be derived
   */
  void setTypeMapOverride(Map<String, Class<?>> typeMap) throws SQLException {
    this.codecContext = getCodecContext().withTypeMap(typeMap);
  }

  /**
   * Gets the binary codec for the specified column.
   *
   * @param columnIndex the 1-based column index
   * @return the binary codec, or null if not available
   * @throws SQLException if an error occurs
   */
  protected @Nullable BinaryCodec getBinaryCodec(int columnIndex) throws SQLException {
    int oid = fields[columnIndex - 1].getOID();
    PgType pgType = connection.getTypeInfo().getPgTypeByOid(oid);
    return connection.getTypeInfo().getCodecRegistry().getBinaryCodec(oid, pgType);
  }

  /**
   * Gets the text codec for the specified column.
   *
   * @param columnIndex the 1-based column index
   * @return the text codec, or null if not available
   * @throws SQLException if an error occurs
   */
  protected @Nullable TextCodec getTextCodec(int columnIndex) throws SQLException {
    int oid = fields[columnIndex - 1].getOID();
    PgType pgType = connection.getTypeInfo().getPgTypeByOid(oid);
    return connection.getTypeInfo().getCodecRegistry().getTextCodec(oid, pgType);
  }

  /**
   * Gets the PgType for the specified column.
   *
   * @param columnIndex the 1-based column index
   * @return the PgType
   * @throws SQLException if an error occurs
   */
  protected PgType getPgType(int columnIndex) throws SQLException {
    int oid = fields[columnIndex - 1].getOID();
    return connection.getTypeInfo().getPgTypeByOid(oid);
  }

  protected ResultSetMetaData createMetaData() throws SQLException {
    return new PgResultSetMetaData(connection, fields);
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    checkClosed();
    if (rsMetaData == null) {
      rsMetaData = createMetaData();
    }
    return rsMetaData;
  }

  PgResultSet(@Nullable Query originalQuery, BaseStatement statement,
      Field[] fields, List<Tuple> tuples,
      @Nullable ResultCursor cursor, int maxRows, int maxFieldSize, int rsType, int rsConcurrency,
      int rsHoldability, boolean adaptiveFetch) throws SQLException {
    // Fail-fast on invalid null inputs
    if (tuples == null) {
      throw new NullPointerException("tuples must be non-null");
    }
    if (fields == null) {
      throw new NullPointerException("fields must be non-null");
    }

    this.originalQuery = originalQuery;
    this.connection = (BaseConnection) statement.getConnection();
    this.statement = statement;
    this.dateTimeHelper = new DateTimeHelper(connection.getQueryExecutor());
    this.fields = fields;
    this.rows = tuples;
    this.cursor = cursor;
    this.maxRows = maxRows;
    this.maxFieldSize = maxFieldSize;
    this.resultsettype = rsType;
    this.resultsetconcurrency = rsConcurrency;
    this.adaptiveFetch = adaptiveFetch;

    // Constructor doesn't have fetch size and can't be sure if fetch size was used so initial value would be the number of rows
    this.lastUsedFetchSize = tuples.size();
  }

  @Override
  public URL getURL(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getURL columnIndex: {0}", columnIndex);
    checkClosed();
    throw Driver.notImplemented(this.getClass(), "getURL(int)");
  }

  @Override
  public URL getURL(String columnName) throws SQLException {
    return getURL(findColumn(columnName));
  }

  @RequiresNonNull({"thisRow"})
  protected @Nullable Object internalGetObject(@Positive int columnIndex, Field field) throws SQLException {
    castNonNull(thisRow, "thisRow");
    switch (getSQLType(columnIndex)) {
      case Types.BOOLEAN:
      case Types.BIT:
        if (field.getOID() == Oid.BOOL) {
          return getBoolean(columnIndex);
        }

        if (field.getOID() == Oid.BIT) {
          // bit(1) returns a Boolean to preserve backwards compatibility. The bit count is the byte
          // length in text but the leading int4 in binary, so read it format-aware. (field.getLength()
          // is unreliable here — it returns 65535.) A null value doesn't matter.
          byte[] data = getRawValue(columnIndex);
          if (data == null || (isBinary(columnIndex) ? ByteConverter.int4(data, 0) : data.length) == 1) {
            return getBoolean(columnIndex);
          }
        }
        // Returning null here will lead to another value processing path for the bit field
        // which will return a PGobject
        return null;
      case Types.SQLXML:
        return getSQLXML(columnIndex);
      case Types.TINYINT:
      case Types.SMALLINT:
      case Types.INTEGER:
        return getInt(columnIndex);
      case Types.BIGINT:
        return getLong(columnIndex);
      case Types.NUMERIC:
      case Types.DECIMAL:
        return getNumeric(columnIndex,
            field.getMod() == -1 ? null : (Integer) decodeNumericScale(field.getMod()), true);
      case Types.REAL:
        return getFloat(columnIndex);
      case Types.FLOAT:
      case Types.DOUBLE:
        return getDouble(columnIndex);
      case Types.CHAR:
      case Types.VARCHAR:
      case Types.LONGVARCHAR:
        return getString(columnIndex);
      case Types.DATE:
        if (connection.getCodecContext().prefersJavaTimeForDate()) {
          return decodeViaCodec(columnIndex);
        }
        return getDate(columnIndex);
      case Types.TIME:
        if (connection.getCodecContext().prefersJavaTimeForTime()) {
          return decodeViaCodec(columnIndex);
        }
        return getTime(columnIndex);
      case Types.TIME_WITH_TIMEZONE:
        if (connection.getCodecContext().prefersJavaTimeForTimetz()) {
          return decodeViaCodec(columnIndex);
        }
        return getTime(columnIndex);
      case Types.TIMESTAMP:
        if (connection.getCodecContext().prefersJavaTimeForTimestamp()) {
          return decodeViaCodec(columnIndex);
        }
        return getTimestamp(columnIndex, null);
      case Types.TIMESTAMP_WITH_TIMEZONE:
        if (connection.getCodecContext().prefersJavaTimeForTimestamptz()) {
          return decodeViaCodec(columnIndex);
        }
        return getTimestamp(columnIndex, null);
      case Types.BINARY:
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
        return getBytes(columnIndex);
      case Types.ARRAY:
        return getArray(columnIndex);
      case Types.CLOB:
        return getClob(columnIndex);
      case Types.BLOB:
        return getBlob(columnIndex);

      default:
        String type = getPGType(columnIndex);

        // if the backend doesn't know the type then coerce to String
        if ("unknown".equals(type)) {
          return getString(columnIndex);
        }

        // Specialized support for ref cursors is neater.
        if ("refcursor".equals(type)) {
          // Fetch all results.
          String cursorName = castNonNull(getString(columnIndex));

          StringBuilder sb = new StringBuilder("FETCH ALL IN ");
          Utils.escapeIdentifier(sb, cursorName);

          // nb: no BEGIN triggered here. This is fine. If someone
          // committed, and the cursor was not holdable (closing the
          // cursor), we avoid starting a new xact and promptly causing
          // it to fail. If the cursor *was* holdable, we don't want a
          // new xact anyway since holdable cursor state isn't affected
          // by xact boundaries. If our caller didn't commit at all, or
          // autocommit was on, then we wouldn't issue a BEGIN anyway.
          //
          // We take the scrollability from the statement, but until
          // we have updatable cursors it must be readonly.
          ResultSet rs =
              connection.execSQLQuery(sb.toString(), resultsettype, ResultSet.CONCUR_READ_ONLY);
          ((PgResultSet) rs).setRefCursor(cursorName);
          // In long-running transactions these backend cursors take up memory space
          // we could close in rs.close(), but if the transaction is closed before the result set,
          // then
          // the cursor no longer exists
          ((PgResultSet) rs).closeRefCursor();
          return rs;
        }

        // UUID and HStore are now handled by codecs via getObject(int).
        // Caller determines what to do (JDBC3 overrides in this case)
        return null;
    }
  }

  @Pure
  @EnsuresNonNull("rows")
  private void checkScrollable() throws SQLException {
    checkClosed();
    if (resultsettype == ResultSet.TYPE_FORWARD_ONLY) {
      throw new PSQLException(
          GT.tr("Operation requires a scrollable ResultSet, but this ResultSet is FORWARD_ONLY."),
          PSQLState.INVALID_CURSOR_STATE);
    }
  }

  @Override
  public boolean absolute(int index) throws SQLException {
    checkScrollable();

    // index is 1-based, but internally we use 0-based indices
    int internalIndex;

    if (index == 0) {
      beforeFirst();
      return false;
    }

    final int rowsSize = rows.size();

    // if index<0, count from the end of the result set, but check
    // to be sure that it is not beyond the first index
    if (index < 0) {
      if (index >= -rowsSize) {
        internalIndex = rowsSize + index;
      } else {
        beforeFirst();
        return false;
      }
    } else {
      // must be the case that index>0,
      // find the correct place, assuming that
      // the index is not too large
      if (index <= rowsSize) {
        internalIndex = index - 1;
      } else {
        afterLast();
        return false;
      }
    }

    currentRow = internalIndex;
    initRowBuffer();
    onInsertRow = false;

    return true;
  }

  @Override
  public void afterLast() throws SQLException {
    checkScrollable();

    final int rowsSize = rows.size();
    if (rowsSize > 0) {
      currentRow = rowsSize;
    }

    onInsertRow = false;
    thisRow = null;
    rowBuffer = null;
  }

  @Override
  public void beforeFirst() throws SQLException {
    checkScrollable();

    if (!rows.isEmpty()) {
      currentRow = -1;
    }

    onInsertRow = false;
    thisRow = null;
    rowBuffer = null;
  }

  @Override
  public boolean first() throws SQLException {
    checkScrollable();

    if (rows.size() <= 0) {
      return false;
    }

    currentRow = 0;
    initRowBuffer();
    onInsertRow = false;

    return true;
  }

  @Override
  public @Nullable Array getArray(String colName) throws SQLException {
    return getArray(findColumn(colName));
  }

  protected Array makeArray(int oid, byte[] value) throws SQLException {
    return new PgArray(connection, oid, value);
  }

  protected Array makeArray(int oid, String value) throws SQLException {
    return new PgArray(connection, oid, value);
  }

  @Pure
  @Override
  public @Nullable Array getArray(int i) throws SQLException {
    byte[] value = getRawValue(i);
    if (value == null) {
      return null;
    }

    int oid = fields[i - 1].getOID();
    if (isBinary(i)) {
      return makeArray(oid, value);
    }
    return makeArray(oid, castNonNull(getFixedString(i)));
  }

  @Override
  public @Nullable BigDecimal getBigDecimal(@Positive int columnIndex) throws SQLException {
    return (BigDecimal) getNumeric(columnIndex, null, false);
  }

  @Override
  public @Nullable BigDecimal getBigDecimal(String columnName) throws SQLException {
    return getBigDecimal(findColumn(columnName));
  }

  @Override
  public @Nullable Blob getBlob(String columnName) throws SQLException {
    return getBlob(findColumn(columnName));
  }

  protected Blob makeBlob(long oid) throws SQLException {
    return new PgBlob(connection, oid);
  }

  @Override
  @Pure
  public @Nullable Blob getBlob(int i) throws SQLException {
    byte[] value = getRawValue(i);
    if (value == null) {
      return null;
    }

    return makeBlob(getLong(i));
  }

  @Override
  public @Nullable Reader getCharacterStream(String columnName) throws SQLException {
    return getCharacterStream(findColumn(columnName));
  }

  @Override
  public @Nullable Reader getCharacterStream(int i) throws SQLException {
    String value = getString(i);
    if (value == null) {
      return null;
    }

    // Version 7.2 supports AsciiStream for all the PG text types
    // As the spec/javadoc for this method indicate this is to be used for
    // large text values (i.e. LONGVARCHAR) PG doesn't have a separate
    // long string datatype, but with toast the text datatype is capable of
    // handling very large values. Thus the implementation ends up calling
    // getString() since there is no current way to stream the value from the server
    return new StringReader(value);
  }

  @Override
  public @Nullable Clob getClob(String columnName) throws SQLException {
    return getClob(findColumn(columnName));
  }

  protected Clob makeClob(long oid) throws SQLException {
    return new PgClob(connection, oid);
  }

  @Override
  @Pure
  public @Nullable Clob getClob(int i) throws SQLException {
    byte[] value = getRawValue(i);
    if (value == null) {
      return null;
    }

    return makeClob(getLong(i));
  }

  @Override
  public int getConcurrency() throws SQLException {
    checkClosed();
    return resultsetconcurrency;
  }

  /**
   * Decodes the (already non-null) raw value of column {@code i} to {@code targetClass} through the
   * column's registered codec, threading {@code cal} so the codec honors the requested time zone.
   * Returns {@code null} when the column has no codec, so the caller can fall back to legacy
   * handling. The temporal codecs own all date/time cross-type conversions (for example
   * {@code getTimestamp} on a DATE column), so the {@code getDate}/{@code getTime}/{@code getTimestamp}
   * methods become a thin dispatch over them.
   */
  private <T> @Nullable T decodeColumnViaCodec(int i, byte[] value, Class<T> targetClass,
      Calendar cal) throws SQLException {
    Field field = getFieldWithCodec(i);
    PgCodecContext ctx = getCodecContext().withCalendar(cal);
    if (isBinary(i)) {
      BinaryCodec codec = field.getBinaryCodec();
      if (codec != null) {
        TypeDescriptor type = field.getPgType();
        return codec.decodeBinaryAs(value, 0, value.length, type, targetClass, ctx);
      }
      return null;
    }
    TextCodec codec = field.getTextCodec();
    if (codec != null) {
      return codec.decodeTextAs(castNonNull(getString(i)), field.getPgType(), targetClass, ctx);
    }
    return null;
  }

  @Override
  public @Nullable Date getDate(
      int i, @Nullable Calendar cal) throws SQLException {
    byte[] value = getRawValue(i);
    if (value == null) {
      return null;
    }

    if (cal == null) {
      cal = getDefaultCalendar();
    }
    Date d = decodeColumnViaCodec(i, value, Date.class, cal);
    if (d != null) {
      return d;
    }
    int col = i - 1;
    int oid = fields[col].getOID();
    throw new PSQLException(
        GT.tr("Cannot convert the column of type {0} to requested type {1}.",
            Oid.toString(oid), "date"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable Time getTime(
      int i, @Nullable Calendar cal) throws SQLException {
    byte[] value = getRawValue(i);
    if (value == null) {
      return null;
    }

    if (cal == null) {
      cal = getDefaultCalendar();
    }
    Time t = decodeColumnViaCodec(i, value, Time.class, cal);
    if (t != null) {
      return t;
    }

    int col = i - 1;
    int oid = fields[col].getOID();
    throw new PSQLException(
        GT.tr("Cannot convert the column of type {0} to requested type {1}.",
            Oid.toString(oid), "time"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Pure
  @Override
  public @Nullable Timestamp getTimestamp(
      int i, @Nullable Calendar cal) throws SQLException {

    byte[] value = getRawValue(i);
    if (value == null) {
      return null;
    }

    if (cal == null) {
      cal = getDefaultCalendar();
    }
    Timestamp t = decodeColumnViaCodec(i, value, Timestamp.class, cal);
    if (t != null) {
      return t;
    }
    int col = i - 1;
    int oid = fields[col].getOID();
    throw new PSQLException(
        GT.tr("Cannot convert the column of type {0} to requested type {1}.",
            Oid.toString(oid), "timestamp"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable Date getDate(
      String c, @Nullable Calendar cal) throws SQLException {
    return getDate(findColumn(c), cal);
  }

  @Override
  public @Nullable Time getTime(
      String c, @Nullable Calendar cal) throws SQLException {
    return getTime(findColumn(c), cal);
  }

  @Override
  public @Nullable Timestamp getTimestamp(
      String c, @Nullable Calendar cal) throws SQLException {
    return getTimestamp(findColumn(c), cal);
  }

  @Override
  public int getFetchDirection() throws SQLException {
    checkClosed();
    return fetchdirection;
  }

  public @Nullable Object getObjectImpl(
      String columnName, @Nullable Map<String, Class<?>> map) throws SQLException {
    return getObjectImpl(findColumn(columnName), map);
  }

  /*
   * This checks against map for the type of column i, and if found returns an object based on that
   * mapping. The class must implement the SQLData interface.
   */
  public @Nullable Object getObjectImpl(
      int i, @Nullable Map<String, Class<?>> map) throws SQLException {
    checkClosed();
    if (map == null || map.isEmpty()) {
      return getObject(i);
    }
    map = IdentifierNormalizingTypeMap.of(map, connection.getTypeInfo());

    byte[] value = getRawValue(i);
    if (value == null) {
      return null;
    }

    // Look up the PostgreSQL type name and check if it's in the map
    String pgTypeName = getPGType(i);
    Class<?> targetClass = map.get(pgTypeName);
    if (targetClass == null) {
      // Type not in map, fall back to default getObject
      return getObject(i);
    }

    // If the target is an SQLData implementation, use CompositeCodec
    if (SQLData.class.isAssignableFrom(targetClass)) {
      Field field = getFieldWithCodec(i);
      PgType pgType = field.getPgType();
      @SuppressWarnings("unchecked")
      Class<? extends SQLData> sqlDataClass = (Class<? extends SQLData>) targetClass;
      PgCodecContext ctx = getCodecContext().withTypeMap(map);
      if (isBinary(i)) {
        return CompositeCodec.INSTANCE.decodeBinaryAs(value, 0, value.length, pgType, sqlDataClass, ctx);
      } else {
        String textValue = getString(i);
        if (textValue == null) {
          return null;
        }
        return CompositeCodec.INSTANCE.decodeTextAs(textValue, pgType, sqlDataClass, ctx);
      }
    }

    // For other mapped types, delegate to getObject(int, Class)
    return getObject(i, targetClass);
  }

  @Override
  public @Nullable Ref getRef(String columnName) throws SQLException {
    return getRef(findColumn(columnName));
  }

  @Override
  public @Nullable Ref getRef(int i) throws SQLException {
    checkClosed();
    // The backend doesn't yet have SQL3 REF types
    throw Driver.notImplemented(this.getClass(), "getRef(int)");
  }

  @Override
  public int getRow() throws SQLException {
    checkClosed();

    if (onInsertRow) {
      return 0;
    }

    final int rowsSize = rows.size();

    if (currentRow < 0 || currentRow >= rowsSize) {
      return 0;
    }

    return rowOffset + currentRow + 1;
  }

  // This one needs some thought, as not all ResultSets come from a statement
  @Override
  public Statement getStatement() throws SQLException {
    checkClosed();
    return statement;
  }

  @Override
  public int getType() throws SQLException {
    checkClosed();
    return resultsettype;
  }

  @Pure
  @Override
  public boolean isAfterLast() throws SQLException {
    checkClosed();
    if (onInsertRow) {
      return false;
    }

    castNonNull(rows, "rows");
    final int rowsSize = rows.size();
    if (rowOffset + rowsSize == 0) {
      return false;
    }
    return currentRow >= rowsSize;
  }

  @Pure
  @Override
  public boolean isBeforeFirst() throws SQLException {
    checkClosed();
    if (onInsertRow) {
      return false;
    }

    return (rowOffset + currentRow) < 0 && !castNonNull(rows, "rows").isEmpty();
  }

  @Override
  public boolean isFirst() throws SQLException {
    checkClosed();
    if (onInsertRow) {
      return false;
    }

    final int rowsSize = rows.size();
    if (rowOffset + rowsSize == 0) {
      return false;
    }

    return (rowOffset + currentRow) == 0;
  }

  @Override
  public boolean isLast() throws SQLException {
    checkClosed();
    if (onInsertRow) {
      return false;
    }

    List<Tuple> rows = castNonNull(this.rows, "rows");
    final int rowsSize = rows.size();

    if (rowsSize == 0) {
      return false; // No rows.
    }

    if (currentRow != (rowsSize - 1)) {
      return false; // Not on the last row of this block.
    }

    // We are on the last row of the current block.

    ResultCursor cursor = this.cursor;
    if (cursor == null) {
      // This is the last block and therefore the last row.
      return true;
    }

    if (maxRows > 0 && rowOffset + currentRow == maxRows) {
      // We are implicitly limited by maxRows.
      return true;
    }

    // Now the more painful case begins.
    // We are on the last row of the current block, but we don't know if the
    // current block is the last block; we must try to fetch some more data to
    // find out.

    // We do a fetch of the next block, then prepend the current row to that
    // block (so currentRow == 0). This works as the current row
    // must be the last row of the current block if we got this far.

    rowOffset += rowsSize - 1; // Discarding all but one row.

    // Work out how many rows maxRows will let us fetch.
    int fetchRows = fetchSize;
    int adaptiveFetchRows = connection.getQueryExecutor()
        .getAdaptiveFetchSize(adaptiveFetch, cursor);

    if (adaptiveFetchRows != -1) {
      fetchRows = adaptiveFetchRows;
    }

    if (maxRows != 0) {
      if (fetchRows == 0 || rowOffset + fetchRows > maxRows) {
        // Fetch would exceed maxRows, limit it.
        fetchRows = maxRows - rowOffset;
      }
    }

    // Do the actual fetch.
    connection.getQueryExecutor()
        .fetch(cursor, new CursorResultHandler(), fetchRows, adaptiveFetch);

    // After fetch, update last used fetch size (could be useful during adaptive fetch).
    lastUsedFetchSize = fetchRows;

    rows = castNonNull(this.rows, "rows");
    // Now prepend our one saved row and move to it.
    rows.add(0, castNonNull(thisRow));
    currentRow = 0;

    // Finally, now we can tell if we're the last row or not.
    return rows.size() == 1;
  }

  @Override
  public boolean last() throws SQLException {
    checkScrollable();
    List<Tuple> rows = castNonNull(this.rows, "rows");
    final int rowsSize = rows.size();
    if (rowsSize <= 0) {
      return false;
    }

    currentRow = rowsSize - 1;
    initRowBuffer();
    onInsertRow = false;

    return true;
  }

  @Override
  public boolean previous() throws SQLException {
    checkScrollable();

    if (onInsertRow) {
      throw new PSQLException(GT.tr("Can''t use relative move methods while on the insert row."),
          PSQLState.INVALID_CURSOR_STATE);
    }

    if (currentRow - 1 < 0) {
      currentRow = -1;
      thisRow = null;
      rowBuffer = null;
      return false;
    } else {
      currentRow--;
    }
    initRowBuffer();
    return true;
  }

  @Override
  public boolean relative(int rows) throws SQLException {
    checkScrollable();

    if (onInsertRow) {
      throw new PSQLException(GT.tr("Can''t use relative move methods while on the insert row."),
          PSQLState.INVALID_CURSOR_STATE);
    }

    // have to add 1 since absolute expects a 1-based index
    int index = currentRow + 1 + rows;
    if (index < 0) {
      beforeFirst();
      return false;
    }
    return absolute(index);
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    checkClosed();
    switch (direction) {
      case ResultSet.FETCH_FORWARD:
        break;
      case ResultSet.FETCH_REVERSE:
      case ResultSet.FETCH_UNKNOWN:
        checkScrollable();
        break;
      default:
        throw new PSQLException(GT.tr("Invalid fetch direction constant: {0}.", direction),
            PSQLState.INVALID_PARAMETER_VALUE);
    }

    this.fetchdirection = direction;
  }

  @Override
  public void cancelRowUpdates() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkClosed();
      if (onInsertRow) {
        throw new PSQLException(GT.tr("Cannot call cancelRowUpdates() when on the insert row."),
            PSQLState.INVALID_CURSOR_STATE);
      }

      if (doingUpdates) {
        doingUpdates = false;

        clearRowBuffer(true);
      }
    }
  }

  @Override
  public void deleteRow() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkUpdateable();

      if (onInsertRow) {
        throw new PSQLException(GT.tr("Cannot call deleteRow() when on the insert row."),
            PSQLState.INVALID_CURSOR_STATE);
      }

      if (isBeforeFirst()) {
        throw new PSQLException(
            GT.tr(
                "Currently positioned before the start of the ResultSet.  You cannot call deleteRow() here."),
            PSQLState.INVALID_CURSOR_STATE);
      }
      if (isAfterLast()) {
        throw new PSQLException(
            GT.tr(
                "Currently positioned after the end of the ResultSet.  You cannot call deleteRow() here."),
            PSQLState.INVALID_CURSOR_STATE);
      }
      List<Tuple> rows = castNonNull(this.rows, "rows");
      if (rows.isEmpty()) {
        throw new PSQLException(GT.tr("There are no rows in this ResultSet."),
            PSQLState.INVALID_CURSOR_STATE);
      }

      List<PrimaryKey> primaryKeys = castNonNull(this.primaryKeys, "primaryKeys");
      int numKeys = primaryKeys.size();
      PreparedStatement deleteStatement = this.deleteStatement;
      if (deleteStatement == null) {
        StringBuilder deleteSQL =
            new StringBuilder("DELETE FROM ").append(onlyTable).append(tableName).append(" where ");

        for (int i = 0; i < numKeys; i++) {
          Utils.escapeIdentifier(deleteSQL, primaryKeys.get(i).name);
          deleteSQL.append(" = ?");
          if (i < numKeys - 1) {
            deleteSQL.append(" and ");
          }
        }

        this.deleteStatement = deleteStatement = connection.prepareStatement(deleteSQL.toString());
      }
      deleteStatement.clearParameters();

      for (int i = 0; i < numKeys; i++) {
        deleteStatement.setObject(i + 1, primaryKeys.get(i).getValue());
      }

      deleteStatement.executeUpdate();

      rows.remove(currentRow);
      currentRow--;
      moveToCurrentRow();
    }
  }

  @Override
  public void insertRow() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkUpdateable();
      castNonNull(rows, "rows");
      if (!onInsertRow) {
        throw new PSQLException(GT.tr("Not on the insert row."), PSQLState.INVALID_CURSOR_STATE);
      }
      HashMap<String, @Nullable Object> updateValues = this.updateValues;
      if (updateValues == null || updateValues.isEmpty()) {
        throw new PSQLException(GT.tr("You must specify at least one column value to insert a row."),
            PSQLState.INVALID_PARAMETER_VALUE);
      }

      // loop through the keys in the insertTable and create the sql statement
      // we have to create the sql every time since the user could insert different
      // columns each time

      StringBuilder insertSQL = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
      StringBuilder paramSQL = new StringBuilder(") values (");

      Iterator<String> columnNames = updateValues.keySet().iterator();
      int numColumns = updateValues.size();

      for (int i = 0; columnNames.hasNext(); i++) {
        String columnName = columnNames.next();

        Utils.escapeIdentifier(insertSQL, columnName);
        if (i < numColumns - 1) {
          insertSQL.append(", ");
          paramSQL.append("?,");
        } else {
          paramSQL.append("?)");
        }

      }

      insertSQL.append(paramSQL.toString());
      PreparedStatement insertStatement = null;

      Tuple rowBuffer = castNonNull(this.rowBuffer);
      try {
        insertStatement = connection.prepareStatement(insertSQL.toString(), Statement.RETURN_GENERATED_KEYS);

        Iterator<Map.Entry<String, @Nullable Object>> values = updateValues.entrySet().iterator();

        for (int i = 1; values.hasNext(); i++) {
          Map.Entry<String, @Nullable Object> entry = values.next();
          bindUpdateValue(insertStatement, i, entry.getKey(), entry.getValue());
        }

        insertStatement.executeUpdate();

        if (usingOID) {
          // we have to get the last inserted OID and put it in the resultset

          long insertedOID = ((PgStatement) insertStatement).getLastOID();

          updateValues.put("oid", insertedOID);

        }

        // update the underlying row to the new inserted data
        updateRowBuffer(insertStatement, rowBuffer, castNonNull(updateValues));
      } finally {
        JdbcBlackHole.close(insertStatement);
      }

      castNonNull(rows).add(rowBuffer);

      // we should now reflect the current data in thisRow
      // that way getXXX will get the newly inserted data
      thisRow = rowBuffer;

      // need to clear this in case of another insert
      clearRowBuffer(false);
    }
  }

  @Override
  public void moveToCurrentRow() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkUpdateable();
      castNonNull(rows, "rows");

      if (currentRow < 0 || currentRow >= rows.size()) {
        thisRow = null;
        rowBuffer = null;
      } else {
        initRowBuffer();
      }

      onInsertRow = false;
      doingUpdates = false;
    }
  }

  @Override
  public void moveToInsertRow() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkUpdateable();

      // make sure the underlying data is null
      clearRowBuffer(false);

      onInsertRow = true;
      doingUpdates = false;
    }
  }

  // rowBuffer is the temporary storage for the row
  private void clearRowBuffer(boolean copyCurrentRow) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      // inserts want an empty array while updates want a copy of the current row
      if (copyCurrentRow) {
        rowBuffer = castNonNull(thisRow, "thisRow").updateableCopy();
      } else {
        rowBuffer = new Tuple(fields.length);
      }

      // clear the updateValues hash map for the next set of updates
      HashMap<String, @Nullable Object> updateValues = this.updateValues;
      if (updateValues != null) {
        updateValues.clear();
      }
      HashMap<String, SQLType> updateSqlTypes = this.updateSqlTypes;
      if (updateSqlTypes != null) {
        updateSqlTypes.clear();
      }
    }
  }

  @Override
  public boolean rowDeleted() throws SQLException {
    checkClosed();
    return false;
  }

  @Override
  public boolean rowInserted() throws SQLException {
    checkClosed();
    return false;
  }

  @Override
  public boolean rowUpdated() throws SQLException {
    checkClosed();
    return false;
  }

  @Override
  public void updateAsciiStream(@Positive int columnIndex,
      @Nullable InputStream x, int length)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (x == null) {
        updateNull(columnIndex);
        return;
      }

      try {
        InputStreamReader reader = new InputStreamReader(x, StandardCharsets.US_ASCII);
        char[] data = new char[length];
        int numRead = 0;
        while (true) {
          int n = reader.read(data, numRead, length - numRead);
          if (n == -1) {
            break;
          }

          numRead += n;

          if (numRead == length) {
            break;
          }
        }
        updateString(columnIndex, new String(data, 0, numRead));
      } catch (IOException ie) {
        throw new PSQLException(GT.tr("Provided InputStream failed."), null, ie);
      }
    }
  }

  @Override
  public void updateBigDecimal(@Positive int columnIndex, @Nullable BigDecimal x)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateValue(columnIndex, x);
    }
  }

  @Override
  public void updateBinaryStream(@Positive int columnIndex,
      @Nullable InputStream x, int length)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (x == null) {
        updateNull(columnIndex);
        return;
      }

      byte[] data = new byte[length];
      int numRead = 0;
      try {
        while (true) {
          int n = x.read(data, numRead, length - numRead);
          if (n == -1) {
            break;
          }

          numRead += n;

          if (numRead == length) {
            break;
          }
        }
      } catch (IOException ie) {
        throw new PSQLException(GT.tr("Provided InputStream failed."), null, ie);
      }

      if (numRead == length) {
        updateBytes(columnIndex, data);
      } else {
        // the stream contained less data than they said
        // perhaps this is an error?
        byte[] data2 = new byte[numRead];
        System.arraycopy(data, 0, data2, 0, numRead);
        updateBytes(columnIndex, data2);
      }
    }
  }

  @Override
  public void updateBoolean(@Positive int columnIndex, boolean x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateValue(columnIndex, x);
    }
  }

  @Override
  public void updateByte(@Positive int columnIndex, byte x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateValue(columnIndex, String.valueOf(x));
    }
  }

  @Override
  public void updateBytes(@Positive int columnIndex, byte @Nullable [] x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateValue(columnIndex, x);
    }
  }

  @Override
  public void updateCharacterStream(@Positive int columnIndex,
      @Nullable Reader x, int length)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (x == null) {
        updateNull(columnIndex);
        return;
      }

      try {
        char[] data = new char[length];
        int numRead = 0;
        while (true) {
          int n = x.read(data, numRead, length - numRead);
          if (n == -1) {
            break;
          }

          numRead += n;

          if (numRead == length) {
            break;
          }
        }
        updateString(columnIndex, new String(data, 0, numRead));
      } catch (IOException ie) {
        throw new PSQLException(GT.tr("Provided Reader failed."), null, ie);
      }
    }
  }

  @Override
  public void updateDate(@Positive int columnIndex,
      @Nullable Date x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateValue(columnIndex, x);
    }
  }

  @Override
  public void updateDouble(@Positive int columnIndex, double x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateValue(columnIndex, x);
    }
  }

  @Override
  public void updateFloat(@Positive int columnIndex, float x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateValue(columnIndex, x);
    }
  }

  @Override
  public void updateInt(@Positive int columnIndex, int x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateValue(columnIndex, x);
    }
  }

  @Override
  public void updateLong(@Positive int columnIndex, long x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateValue(columnIndex, x);
    }
  }

  @Override
  public void updateNull(@Positive int columnIndex) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkColumnIndex(columnIndex);
      String columnTypeName = getPGType(columnIndex);
      updateValue(columnIndex, new NullObject(columnTypeName));
    }
  }

  @Override
  public void updateObject(
      int columnIndex, @Nullable Object x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateValue(columnIndex, x);
    }
  }

  @Override
  public void updateObject(
      int columnIndex, @Nullable Object x, int scale) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      this.updateObject(columnIndex, x);
    }
  }

  @Override
  public void refreshRow() throws SQLException {
    checkUpdateable();
    if (onInsertRow) {
      throw new PSQLException(GT.tr("Can''t refresh the insert row."),
          PSQLState.INVALID_CURSOR_STATE);
    }

    if (isBeforeFirst() || isAfterLast() || castNonNull(rows, "rows").isEmpty()) {
      return;
    }

    StringBuilder selectSQL = new StringBuilder("select ");

    ResultSetMetaData rsmd = getMetaData();
    PGResultSetMetaData pgmd = (PGResultSetMetaData) rsmd;
    for (int i = 1; i <= rsmd.getColumnCount(); i++) {
      if (i > 1) {
        selectSQL.append(", ");
      }
      Utils.escapeIdentifier(selectSQL, pgmd.getBaseColumnName(i));
    }
    selectSQL.append(" from ").append(onlyTable).append(tableName).append(" where ");

    List<PrimaryKey> primaryKeys = castNonNull(this.primaryKeys, "primaryKeys");
    int numKeys = primaryKeys.size();

    for (int i = 0; i < numKeys; i++) {

      PrimaryKey primaryKey = primaryKeys.get(i);
      Utils.escapeIdentifier(selectSQL, primaryKey.name);
      selectSQL.append(" = ?");

      if (i < numKeys - 1) {
        selectSQL.append(" and ");
      }
    }
    String sqlText = selectSQL.toString();
    if (connection.getLogger().isLoggable(Level.FINE)) {
      connection.getLogger().log(Level.FINE, "selecting {0}", sqlText);
    }
    // because updateable result sets do not yet support binary transfers we must request refresh
    // with updateable result set to get field data in correct format
    PreparedStatement selectStatement = null;
    try {
      selectStatement = connection.prepareStatement(sqlText,
          ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);

      for (int i = 0; i < numKeys; i++) {
        selectStatement.setObject(i + 1, primaryKeys.get(i).getValue());
      }

      PgResultSet rs = (PgResultSet) selectStatement.executeQuery();

      if (rs.next()) {
        // we know that the row is updatable as it was tested above.
        if ( rs.thisRow == null ) {
          rowBuffer = null;
        } else {
          rowBuffer = castNonNull(rs.thisRow).updateableCopy();
        }
      }

      castNonNull(rows).set(currentRow, castNonNull(rowBuffer));
      thisRow = rowBuffer;

      connection.getLogger().log(Level.FINE, "done updates");

      rs.close();
    } finally {
      JdbcBlackHole.close(selectStatement);
    }
  }

  @Override
  public void updateRow() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkUpdateable();

      if (onInsertRow) {
        throw new PSQLException(GT.tr("Cannot call updateRow() when on the insert row."),
            PSQLState.INVALID_CURSOR_STATE);
      }

      List<Tuple> rows = castNonNull(this.rows, "rows");
      if (isBeforeFirst() || isAfterLast() || rows.isEmpty()) {
        throw new PSQLException(
            GT.tr(
                "Cannot update the ResultSet because it is either before the start or after the end of the results."),
            PSQLState.INVALID_CURSOR_STATE);
      }

      if (!doingUpdates) {
        return; // No work pending.
      }

      StringBuilder updateSQL = new StringBuilder("UPDATE " + onlyTable + tableName + " SET  ");

      HashMap<String, @Nullable Object> updateValues = castNonNull(this.updateValues);
      int numColumns = updateValues.size();
      Iterator<String> columns = updateValues.keySet().iterator();

      for (int i = 0; columns.hasNext(); i++) {
        String column = columns.next();
        Utils.escapeIdentifier(updateSQL, column);
        updateSQL.append(" = ?");

        if (i < numColumns - 1) {
          updateSQL.append(", ");
        }
      }

      updateSQL.append(" WHERE ");

      List<PrimaryKey> primaryKeys = castNonNull(this.primaryKeys, "primaryKeys");
      int numKeys = primaryKeys.size();

      for (int i = 0; i < numKeys; i++) {
        PrimaryKey primaryKey = primaryKeys.get(i);
        Utils.escapeIdentifier(updateSQL, primaryKey.name);
        updateSQL.append(" = ?");

        if (i < numKeys - 1) {
          updateSQL.append(" and ");
        }
      }

      String sqlText = updateSQL.toString();
      if (connection.getLogger().isLoggable(Level.FINE)) {
        connection.getLogger().log(Level.FINE, "updating {0}", sqlText);
      }
      PreparedStatement updateStatement = null;
      try {
        updateStatement = connection.prepareStatement(sqlText);

        int i = 0;
        Iterator<Map.Entry<String, @Nullable Object>> iterator = updateValues.entrySet().iterator();
        for (; iterator.hasNext(); i++) {
          Map.Entry<String, @Nullable Object> entry = iterator.next();
          bindUpdateValue(updateStatement, i + 1, entry.getKey(), entry.getValue());
        }

        for (int j = 0; j < numKeys; j++, i++) {
          updateStatement.setObject(i + 1, primaryKeys.get(j).getValue());
        }

        updateStatement.executeUpdate();
      } finally {
        JdbcBlackHole.close(updateStatement);
      }

      Tuple rowBuffer = castNonNull(this.rowBuffer, "rowBuffer");
      updateRowBuffer(null, rowBuffer, updateValues);

      connection.getLogger().log(Level.FINE, "copying data");
      thisRow = rowBuffer.readOnlyCopy();
      rows.set(currentRow, rowBuffer);

      connection.getLogger().log(Level.FINE, "done updates");
      updateValues.clear();
      if (updateSqlTypes != null) {
        updateSqlTypes.clear();
      }
      doingUpdates = false;
    }
  }

  @Override
  public void updateShort(@Positive int columnIndex, short x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateValue(columnIndex, x);
    }
  }

  @Override
  public void updateString(@Positive int columnIndex, @Nullable String x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateValue(columnIndex, x);
    }
  }

  @Override
  public void updateTime(@Positive int columnIndex, @Nullable Time x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateValue(columnIndex, x);
    }
  }

  @Override
  public void updateTimestamp(
      int columnIndex, @Nullable Timestamp x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateValue(columnIndex, x);
    }
  }

  @Override
  public void updateNull(String columnName) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateNull(findColumn(columnName));
    }
  }

  @Override
  public void updateBoolean(String columnName, boolean x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateBoolean(findColumn(columnName), x);
    }
  }

  @Override
  public void updateByte(String columnName, byte x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateByte(findColumn(columnName), x);
    }
  }

  @Override
  public void updateShort(String columnName, short x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateShort(findColumn(columnName), x);
    }
  }

  @Override
  public void updateInt(String columnName, int x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateInt(findColumn(columnName), x);
    }
  }

  @Override
  public void updateLong(String columnName, long x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateLong(findColumn(columnName), x);
    }
  }

  @Override
  public void updateFloat(String columnName, float x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateFloat(findColumn(columnName), x);
    }
  }

  @Override
  public void updateDouble(String columnName, double x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateDouble(findColumn(columnName), x);
    }
  }

  @Override
  public void updateBigDecimal(
      String columnName, @Nullable BigDecimal x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateBigDecimal(findColumn(columnName), x);
    }
  }

  @Override
  public void updateString(
      String columnName, @Nullable String x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateString(findColumn(columnName), x);
    }
  }

  @Override
  public void updateBytes(
      String columnName, byte @Nullable [] x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateBytes(findColumn(columnName), x);
    }
  }

  @Override
  public void updateDate(
      String columnName, @Nullable Date x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateDate(findColumn(columnName), x);
    }
  }

  @Override
  public void updateTime(
      String columnName, @Nullable Time x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateTime(findColumn(columnName), x);
    }
  }

  @Override
  public void updateTimestamp(
      String columnName, @Nullable Timestamp x)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateTimestamp(findColumn(columnName), x);
    }
  }

  @Override
  public void updateAsciiStream(
      String columnName, @Nullable InputStream x, int length)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateAsciiStream(findColumn(columnName), x, length);
    }
  }

  @Override
  public void updateBinaryStream(
      String columnName, @Nullable InputStream x, int length)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateBinaryStream(findColumn(columnName), x, length);
    }
  }

  @Override
  public void updateCharacterStream(
      String columnName, @Nullable Reader reader,
      int length) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateCharacterStream(findColumn(columnName), reader, length);
    }
  }

  @Override
  public void updateObject(
      String columnName, @Nullable Object x, int scale)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateObject(findColumn(columnName), x);
    }
  }

  @Override
  public void updateObject(
      String columnName, @Nullable Object x) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      updateObject(findColumn(columnName), x);
    }
  }

  /**
   * Is this ResultSet updateable?
   */

  boolean isUpdateable() throws SQLException {
    checkClosed();

    if (resultsetconcurrency == ResultSet.CONCUR_READ_ONLY) {
      throw new PSQLException(
          GT.tr("ResultSets with concurrency CONCUR_READ_ONLY cannot be updated."),
          PSQLState.INVALID_CURSOR_STATE);
    }

    if (updateable) {
      return true;
    }

    connection.getLogger().log(Level.FINE, "checking if rs is updateable");

    parseQuery();

    if (tableName == null) {
      connection.getLogger().log(Level.FINE, "tableName is not found");
      return false;
    }

    if (!singleTable) {
      connection.getLogger().log(Level.FINE, "not a single table");
      return false;
    }

    usingOID = false;

    connection.getLogger().log(Level.FINE, "getting primary keys");

    //
    // Contains the primary key?
    //

    List<PrimaryKey> primaryKeys = new ArrayList<>();
    this.primaryKeys = primaryKeys;

    int i = 0;
    int numPKcolumns = 0;

    // otherwise go and get the primary keys and create a list of keys
    @Nullable String[] s = quotelessTableName(castNonNull(tableName));
    String quotelessTableName = castNonNull(s[0]);
    @Nullable String quotelessSchemaName = s[1];
    ResultSet rs = ((PgDatabaseMetaData) connection.getMetaData()).getPrimaryUniqueKeys("",
        quotelessSchemaName, quotelessTableName);

    String lastConstraintName = null;

    while (rs.next()) {
      String constraintName = castNonNull(rs.getString(6)); // get the constraintName
      if (lastConstraintName == null || !lastConstraintName.equals(constraintName)) {
        if (lastConstraintName != null) {
          if (i == numPKcolumns && numPKcolumns > 0) {
            break;
          }
          connection.getLogger().log(Level.FINE, "no of keys={0} from constraint {1}", new Object[]{i, lastConstraintName});
        }
        i = 0;
        numPKcolumns = 0;

        primaryKeys.clear();
        lastConstraintName = constraintName;
      }
      numPKcolumns++;

      boolean isNotNull = rs.getBoolean("IS_NOT_NULL");

      /* make sure that only unique keys with all non-null attributes are handled */
      if (isNotNull) {
        String columnName = castNonNull(rs.getString(4)); // get the columnName
        int index = findColumnIndex(columnName);

        /* make sure that the user has included the primary key in the resultset */
        if (index > 0) {
          i++;
          primaryKeys.add(new PrimaryKey(index, columnName)); // get the primary key information
        }
      }
    }

    rs.close();
    connection.getLogger().log(Level.FINE, "no of keys={0} from constraint {1}", new Object[]{i, lastConstraintName});

    /*
    it is only updatable if the primary keys are available in the resultset
     */
    updateable = (i == numPKcolumns) && (numPKcolumns > 0);

    connection.getLogger().log(Level.FINE, "checking primary key {0}", updateable);

    /*
      if we haven't found a primary key we can check to see if the query includes the oid
      This is now a questionable check as oid's have been deprecated. Might still be useful for
      catalog tables, but again the query would have to include the oid.
     */
    if (!updateable) {
      int oidIndex = findColumnIndex("oid"); // 0 if not present

      // oidIndex will be >0 if the oid was in the select list
      if (oidIndex > 0) {
        primaryKeys.add(new PrimaryKey(oidIndex, "oid"));
        usingOID = true;
        updateable = true;
      }
    }

    if (!updateable) {
      throw new PSQLException(GT.tr("No eligible primary or unique key found for table {0}.", tableName),
          PSQLState.INVALID_CURSOR_STATE);
    }

    return updateable;
  }

  /**
   * Turn on/off adaptive fetch for ResultSet.
   *
   * @param adaptiveFetch desired state of adaptive fetch.
   * @throws SQLException exception returned if ResultSet is closed
   */
  public void setAdaptiveFetch(boolean adaptiveFetch) throws SQLException {
    checkClosed();
    updateQueryInsideAdaptiveFetchCache(adaptiveFetch);
    this.adaptiveFetch = adaptiveFetch;
  }

  /**
   * Update adaptive fetch cache during changing state of adaptive fetch inside
   * ResultSet. Update inside AdaptiveFetchCache is required to collect data about max result
   * row length for that query to compute adaptive fetch size.
   *
   * @param newAdaptiveFetch new state of adaptive fetch
   */
  private void updateQueryInsideAdaptiveFetchCache(boolean newAdaptiveFetch) {
    if (Objects.nonNull(cursor)) {
      ResultCursor resultCursor = cursor;
      if (!this.adaptiveFetch && newAdaptiveFetch) {
        // If we are here, that means we want to be added to adaptive fetch.
        connection.getQueryExecutor().addQueryToAdaptiveFetchCache(true, resultCursor);
      }

      if (this.adaptiveFetch && !newAdaptiveFetch && Objects.nonNull(cursor)) {
        // If we are here, that means we want to be removed from adaptive fetch.
        connection.getQueryExecutor().removeQueryFromAdaptiveFetchCache(true, resultCursor);
      }
    }
  }

  /**
   * Get state of adaptive fetch for resultSet.
   *
   * @return state of adaptive fetch (turned on or off)
   * @throws SQLException exception returned if ResultSet is closed
   */
  public boolean getAdaptiveFetch() throws SQLException {
    checkClosed();
    return adaptiveFetch;
  }

  /**
   * Cracks out the table name and schema (if it exists) from a fully qualified table name.
   *
   * @param fullname string that we are trying to crack. Test cases:
   *
   *        <pre>
   *
   *                 Table: table
   *                                 ()
   *
   *                 "Table": Table
   *                                 ()
   *
   *                 Schema.Table:
   *                                 table (schema)
   *
   *                                 "Schema"."Table": Table
   *                                                 (Schema)
   *
   *                                 "Schema"."Dot.Table": Dot.Table
   *                                                 (Schema)
   *
   *                                 Schema."Dot.Table": Dot.Table
   *                                                 (schema)
   *
   *        </pre>
   *
   * @return String array with element zero always being the tablename and element 1 the schema name
   *         which may be a zero length string.
   */
  public static @Nullable String[] quotelessTableName(String fullname) {

    @Nullable String[] parts = new String[]{null, ""};
    StringBuilder acc = new StringBuilder();
    boolean betweenQuotes = false;
    for (int i = 0; i < fullname.length(); i++) {
      char c = fullname.charAt(i);
      switch (c) {
        case '"':
          if ((i < fullname.length() - 1) && (fullname.charAt(i + 1) == '"')) {
            // two consecutive quotes - keep one
            i++;
            acc.append(c); // keep the quote
          } else { // Discard it
            betweenQuotes = !betweenQuotes;
          }
          break;
        case '.':
          if (betweenQuotes) { // Keep it
            acc.append(c);
          } else { // Have schema name
            parts[1] = acc.toString();
            acc = new StringBuilder();
          }
          break;
        default:
          acc.append(betweenQuotes ? c : Character.toLowerCase(c));
          break;
      }
    }
    // Always put table in slot 0
    parts[0] = acc.toString();
    return parts;
  }

  private void parseQuery() {
    Query originalQuery = this.originalQuery;
    if (originalQuery == null) {
      return;
    }
    String sql = originalQuery.toString(null);
    StringTokenizer st = new StringTokenizer(sql, " \r\t\n");
    boolean tableFound = false;
    boolean tablesChecked = false;
    String name = "";

    singleTable = true;

    while (!tableFound && !tablesChecked && st.hasMoreTokens()) {
      name = st.nextToken();
      if ("from".equalsIgnoreCase(name)) {
        tableName = st.nextToken();
        if ("only".equalsIgnoreCase(tableName)) {
          tableName = st.nextToken();
          onlyTable = "ONLY ";
        }
        tableFound = true;
      }
    }
  }

  private void setRowBufferColumn(Tuple rowBuffer,
      int columnIndex, @Nullable Object valueObject) throws SQLException {
    if (valueObject == null) {
      rowBuffer.set(columnIndex, null);
      return;
    }
    boolean binary = isBinary(columnIndex + 1);
    if (valueObject instanceof PGobject && !binary) {
      // PGobject already carries its own text representation
      // (PgStruct.getValue() also lazily encodes its attributes).
      String value = ((PGobject) valueObject).getValue();
      rowBuffer.set(columnIndex, value == null ? null : connection.encodeString(value));
      return;
    }
    switch (getSQLType(columnIndex + 1)) {

      // boolean needs to be formatted as t or f instead of true or false
      case Types.BIT:
      case Types.BOOLEAN:
        if (!binary) {
          rowBuffer.set(columnIndex, connection
              .encodeString((Boolean) valueObject ? "t" : "f"));
          break;
        }
        encodeRowBufferColumnViaCodec(rowBuffer, columnIndex, valueObject);
        break;
      //
      // toString() isn't enough for date and time types; we must format it correctly
      // or we won't be able to re-parse it.
      //
      case Types.DATE:
        if (!binary) {
          // getObject(int, LocalDate.class) returns LocalDate, so updating the row buffer with
          // such a value must not assume it is always a java.sql.Date.
          String stringValue = valueObject instanceof LocalDate
              ? getTimestampUtils().toString((LocalDate) valueObject)
              : getTimestampUtils().toString(getDefaultCalendar(), (Date) valueObject);
          rowBuffer.set(columnIndex, connection.encodeString(stringValue));
          break;
        }
        encodeRowBufferColumnViaCodec(rowBuffer, columnIndex, valueObject);
        break;

      case Types.TIME:
        if (!binary) {
          // time and timetz both map to Types.TIME, so the value can be java.sql.Time,
          // java.time.LocalTime (time) or java.time.OffsetTime (timetz).
          String stringValue;
          if (valueObject instanceof OffsetTime) {
            stringValue = getTimestampUtils().toString((OffsetTime) valueObject);
          } else if (valueObject instanceof LocalTime) {
            stringValue = getTimestampUtils().toString((LocalTime) valueObject);
          } else {
            stringValue = getTimestampUtils().toString(getDefaultCalendar(), (Time) valueObject);
          }
          rowBuffer.set(columnIndex, connection.encodeString(stringValue));
          break;
        }
        encodeRowBufferColumnViaCodec(rowBuffer, columnIndex, valueObject);
        break;

      case Types.TIMESTAMP:
      case Types.TIMESTAMP_WITH_TIMEZONE:
        if (!binary) {
          // timestamp and timestamptz both map to Types.TIMESTAMP, so the value can be
          // java.sql.Timestamp, java.time.LocalDateTime (timestamp)
          // or java.time.OffsetDateTime (timestamptz).
          String stringValue;
          if (valueObject instanceof OffsetDateTime) {
            stringValue = getTimestampUtils().toString((OffsetDateTime) valueObject);
          } else if (valueObject instanceof LocalDateTime) {
            stringValue = getTimestampUtils().toString((LocalDateTime) valueObject);
          } else {
            stringValue = getTimestampUtils().toString(getDefaultCalendar(), (Timestamp) valueObject);
          }
          rowBuffer.set(columnIndex, connection.encodeString(stringValue));
          break;
        }
        encodeRowBufferColumnViaCodec(rowBuffer, columnIndex, valueObject);
        break;

      case Types.NULL:
        // Should never happen?
        break;

      case Types.BINARY:
      case Types.LONGVARBINARY:
      case Types.VARBINARY:
        if (binary) {
          rowBuffer.set(columnIndex, (byte[]) valueObject);
        } else {
          Charset charset;
          try {
            charset = Charset.forName(connection.getEncoding().name());
          } catch (UnsupportedCharsetException e) {
            throw new PSQLException(
                GT.tr("The JVM claims not to support the encoding: {0}", connection.getEncoding().name()),
                PSQLState.UNEXPECTED_ERROR, e);
          }
          byte[] bytes = PGbytea.toPGString((byte[]) valueObject).getBytes(charset);
          rowBuffer.set(columnIndex, bytes);
        }
        break;

      default:
        encodeRowBufferColumnViaCodec(rowBuffer, columnIndex, valueObject);
        break;
    }
  }

  /**
   * Encodes {@code valueObject} into {@code rowBuffer} via the codec
   * registry, choosing the binary or text representation that matches
   * the column's wire format. Falls back to {@link String#valueOf} for
   * columns whose type has no registered codec, mirroring the legacy
   * default-branch behaviour.
   */
  private void encodeRowBufferColumnViaCodec(
      Tuple rowBuffer, int columnIndex, Object valueObject) throws SQLException {
    PgCodecContext ctx = getCodecContext();
    int oid = fields[columnIndex].getOID();
    PgType pgType = connection.getTypeInfo().getPgTypeByOid(oid);
    if (isBinary(columnIndex + 1)) {
      BinaryCodec codec = ctx.getCodecs().getBinaryCodec(oid, pgType);
      if (codec != null) {
        rowBuffer.set(columnIndex, codec.encodeBinary(valueObject, pgType, ctx));
        return;
      }
    } else {
      TextCodec codec = ctx.getCodecs().getTextCodec(oid, pgType);
      if (codec != null) {
        String text = codec.encodeText(valueObject, pgType, ctx);
        rowBuffer.set(columnIndex, text == null ? null : connection.encodeString(text));
        return;
      }
    }
    rowBuffer.set(columnIndex, connection.encodeString(String.valueOf(valueObject)));
  }

  /**
   * Binds one column of a pending {@code insertRow()}/{@code updateRow()} onto {@code statement}.
   * Routes through {@link PreparedStatement#setObject(int, Object, SQLType)} when the column was
   * last set via one of the {@code updateObject(..., SQLType, ...)} overloads, otherwise falls
   * back to the type-inferring {@link PreparedStatement#setObject(int, Object)}.
   */
  private void bindUpdateValue(PreparedStatement statement, int parameterIndex, String columnName,
      @Nullable Object value) throws SQLException {
    HashMap<String, SQLType> updateSqlTypes = this.updateSqlTypes;
    SQLType targetSqlType = updateSqlTypes == null ? null : updateSqlTypes.get(columnName);
    if (targetSqlType != null) {
      statement.setObject(parameterIndex, value, targetSqlType);
    } else {
      statement.setObject(parameterIndex, value);
    }
  }

  private void updateRowBuffer(@Nullable PreparedStatement insertStatement,
      Tuple rowBuffer, Map<String, @Nullable Object> updateValues) throws SQLException {
    for (Map.Entry<String, @Nullable Object> entry : updateValues.entrySet()) {
      int columnIndex = findColumn(entry.getKey()) - 1;
      @Nullable Object valueObject = entry.getValue();
      setRowBufferColumn(rowBuffer, columnIndex, valueObject);
    }

    if (insertStatement == null) {
      return;
    }
    final ResultSet generatedKeys = insertStatement.getGeneratedKeys();
    try {
      generatedKeys.next();

      List<PrimaryKey> primaryKeys = castNonNull(this.primaryKeys);
      int numKeys = primaryKeys.size();

      for (int i = 0; i < numKeys; i++) {
        final PrimaryKey key = primaryKeys.get(i);
        int columnIndex = key.index - 1;
        Object valueObject = generatedKeys.getObject(key.name);
        setRowBufferColumn(rowBuffer, columnIndex, valueObject);
      }
    } finally {
      generatedKeys.close();
    }
  }

  public class CursorResultHandler extends ResultHandlerBase {

    @Override
    public void handleResultRows(Query fromQuery, Field[] fields, List<Tuple> tuples,
        @Nullable ResultCursor cursor) {
      PgResultSet.this.rows = tuples;
      PgResultSet.this.cursor = cursor;
    }

    @Override
    public void handleCommandStatus(String status, long updateCount, long insertOID) {
      handleError(new PSQLException(GT.tr("Unexpected command status: {0}.", status),
          PSQLState.PROTOCOL_VIOLATION));
    }

    @Override
    public void handleWarning(SQLWarning warning) {
      PgResultSet.this.addWarning(warning);
    }
  }

  public BaseStatement getPGStatement() {
    return statement;
  }

  //
  // Backwards compatibility with PGRefCursorResultSet
  //

  private @Nullable String refCursorName;

  @Override
  @SuppressWarnings("deprecation")
  public @Nullable String getRefCursor() {
    // Can't check this because the PGRefCursorResultSet
    // interface doesn't allow throwing a SQLException
    //
    // checkClosed();
    return refCursorName;
  }

  private void setRefCursor(String refCursorName) {
    this.refCursorName = refCursorName;
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    checkClosed();
    if (rows < 0) {
      throw new PSQLException(GT.tr("Fetch size must be a value greater than or equal to 0."),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    fetchSize = rows;
  }

  @Override
  public int getFetchSize() throws SQLException {
    checkClosed();
    if (adaptiveFetch) {
      return lastUsedFetchSize;
    } else {
      return fetchSize;
    }
  }

  /**
   * Get fetch size used during last fetch. Returned value can be useful if using adaptive
   * fetch.
   *
   * @return fetch size used during last fetch.
   * @throws SQLException exception returned if ResultSet is closed
   */
  public int getLastUsedFetchSize() throws SQLException {
    checkClosed();
    return lastUsedFetchSize;
  }

  @Override
  public boolean next() throws SQLException {
    checkClosed();
    castNonNull(rows, "rows");

    if (onInsertRow) {
      throw new PSQLException(GT.tr("Can''t use relative move methods while on the insert row."),
          PSQLState.INVALID_CURSOR_STATE);
    }

    if (currentRow + 1 >= rows.size()) {
      ResultCursor cursor = this.cursor;
      if (cursor == null || (maxRows > 0 && rowOffset + rows.size() >= maxRows)) {
        currentRow = rows.size();
        thisRow = null;
        rowBuffer = null;
        return false; // End of the resultset.
      }

      // Ask for some more data.
      rowOffset += rows.size(); // We are discarding some data.

      int fetchRows = fetchSize;
      int adaptiveFetchRows = connection.getQueryExecutor()
          .getAdaptiveFetchSize(adaptiveFetch, cursor);

      if (adaptiveFetchRows != -1) {
        fetchRows = adaptiveFetchRows;
      }

      if (maxRows != 0) {
        if (fetchRows == 0 || rowOffset + fetchRows > maxRows) {
          // Fetch would exceed maxRows, limit it.
          fetchRows = maxRows - rowOffset;
        }
      }

      // Execute the fetch and update this resultset.
      connection.getQueryExecutor()
          .fetch(cursor, new CursorResultHandler(), fetchRows, adaptiveFetch);

      // .fetch(...) could update this.cursor, and cursor==null means
      // there are no more rows to fetch
      closeRefCursor();

      // After fetch, update last used fetch size (could be useful for adaptive fetch).
      lastUsedFetchSize = fetchRows;

      currentRow = 0;

      // Test the new rows array.
      if (rows == null || rows.isEmpty()) {
        thisRow = null;
        rowBuffer = null;
        return false;
      }
    } else {
      currentRow++;
    }

    initRowBuffer();
    return true;
  }

  @Override
  public void close() throws SQLException {
    try {
      closeInternally();
    } finally {
      ((PgStatement) statement).checkCompletion();
    }
  }

  /*
  used by PgStatement.closeForNextExecution to avoid
  closing the firstUnclosedResult twice.
  checkCompletion above modifies firstUnclosedResult
  fixes issue #684
   */
  protected void closeInternally() throws SQLException {
    // release resources held (memory for tuples)
    rows = null;
    thisRow = null;
    rowBuffer = null;
    JdbcBlackHole.close(deleteStatement);
    deleteStatement = null;
    if (cursor != null) {
      cursor.close();
      cursor = null;
    }
    closeRefCursor();
  }

  /**
   * Closes {@code <unnamed portal 1>} if no more fetch calls expected ({@code cursor==null})
   * @throws SQLException if portal close fails
   */
  private void closeRefCursor() throws SQLException {
    String refCursorName = this.refCursorName;
    if (refCursorName == null || cursor != null) {
      return;
    }
    try {
      if (connection.getTransactionState() == TransactionState.OPEN) {
        StringBuilder sb = new StringBuilder("CLOSE ");
        Utils.escapeIdentifier(sb, refCursorName);
        connection.execSQLUpdate(sb.toString());
      }
    } finally {
      this.refCursorName = null;
    }
  }

  @Override
  public boolean wasNull() throws SQLException {
    checkClosed();
    return wasNullFlag;
  }

  @Pure
  @Override
  public @Nullable String getString(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getString columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return null;
    }

    // varchar in binary is same as text, other binary fields are converted to their text format
    if (isBinary(columnIndex) && getSQLType(columnIndex) != Types.VARCHAR) {
      Field field = getFieldWithCodec(columnIndex);
      BinaryCodec codec = field.getBinaryCodec();
      if (codec != null) {
        PgType pgType = field.getPgType();
        PgCodecContext ctx = getCodecContext();
        return codec.decodeAsString(value, 0, value.length, pgType, ctx);
      }
      // Fallback for types without a binary codec
      castNonNull(thisRow, "thisRow");
      Object obj = internalGetObject(columnIndex, field);
      if (obj == null) {
        obj = getObject(columnIndex);
        if (obj == null) {
          return null;
        }
        return obj.toString();
      }
      return trimString(columnIndex, obj.toString());
    }

    Encoding encoding = connection.getEncoding();
    try {
      return trimString(columnIndex, encoding.decode(value));
    } catch (IOException ioe) {
      throw new PSQLException(
          GT.tr(
              "Invalid character data was found.  This is most likely caused by stored data containing characters that are invalid for the character set the database was created in.  The most common example of this is storing 8bit data in a SQL_ASCII database."),
          PSQLState.DATA_ERROR, ioe);
    }
  }

  /**
   * Retrieves the value of the designated column in the current row of this <code>ResultSet</code>
   * object as a <code>boolean</code> in the Java programming language.
   *
   * <p>If the designated column has a Character datatype and is one of the following values: "1",
   * "true", "t", "yes", "y" or "on", a value of <code>true</code> is returned. If the designated
   * column has a Character datatype and is one of the following values: "0", "false", "f", "no",
   * "n" or "off", a value of <code>false</code> is returned. Leading or trailing whitespace is
   * ignored, and case does not matter.</p>
   *
   * <p>If the designated column has a Numeric datatype and is a 1, a value of <code>true</code> is
   * returned. If the designated column has a Numeric datatype and is a 0, a value of
   * <code>false</code> is returned.</p>
   *
   * @param columnIndex the first column is 1, the second is 2, ...
   * @return the column value; if the value is SQL <code>NULL</code>, the value returned is
   *         <code>false</code>
   * @throws SQLException if the columnIndex is not valid; if a database access error occurs; if
   *         this method is called on a closed result set or is an invalid cast to boolean type.
   * @see <a href="https://www.postgresql.org/docs/current/static/datatype-boolean.html">PostgreSQL
   *      Boolean Type</a>
   */
  @Pure
  @Override
  public boolean getBoolean(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getBoolean columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return false;
    }

    Field field = getFieldWithCodec(columnIndex);
    PgType pgType = field.getPgType();
    PgCodecContext ctx = getCodecContext();

    if (isBinary(columnIndex)) {
      BinaryCodec codec = field.getBinaryCodec();
      if (codec == null) {
        throw cannotConvert(field, "boolean");
      }
      return PrimitiveDecoders.asBoolean(codec, value, pgType, ctx);
    }

    // Text format
    TextCodec codec = field.getTextCodec();
    if (codec == null) {
      throw cannotConvert(field, "boolean");
    }
    String s = castNonNull(getString(columnIndex));
    return PrimitiveDecoders.asBoolean(codec,s, pgType, ctx);
  }

  @Override
  public byte getByte(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getByte columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return 0; // SQL NULL
    }

    Field field = getFieldWithCodec(columnIndex);
    PgType pgType = field.getPgType();
    PgCodecContext ctx = getCodecContext();

    if (isBinary(columnIndex)) {
      BinaryCodec codec = field.getBinaryCodec();
      if (codec == null) {
        throw cannotConvert(field, "byte");
      }
      int intValue = PrimitiveDecoders.asInt(codec, value, pgType, ctx);
      if (intValue < Byte.MIN_VALUE || intValue > Byte.MAX_VALUE) {
        throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "byte", intValue),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (byte) intValue;
    }

    // Text format - delegate to codec. BOOL→numeric is handled by BoolCodec
    // which gates on the convertBooleanToNumeric connection property.
    TextCodec codec = field.getTextCodec();
    if (codec == null) {
      throw cannotConvert(field, "byte");
    }
    int intValue = PrimitiveDecoders.asIntFromTextBytes(codec, value, pgType, ctx);
    if (intValue < Byte.MIN_VALUE || intValue > Byte.MAX_VALUE) {
      throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "byte", intValue),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return (byte) intValue;
  }

  @Override
  public short getShort(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getShort columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return 0; // SQL NULL
    }

    Field field = getFieldWithCodec(columnIndex);
    PgType pgType = field.getPgType();
    PgCodecContext ctx = getCodecContext();

    if (isBinary(columnIndex)) {
      BinaryCodec codec = field.getBinaryCodec();
      if (codec == null) {
        throw cannotConvert(field, "short");
      }
      int intValue = PrimitiveDecoders.asInt(codec, value, pgType, ctx);
      if (intValue < Short.MIN_VALUE || intValue > Short.MAX_VALUE) {
        throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "short", intValue),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (short) intValue;
    }

    // Text format - delegate to codec. BOOL→numeric is handled by BoolCodec
    // which gates on the convertBooleanToNumeric connection property.
    TextCodec codec = field.getTextCodec();
    if (codec == null) {
      throw cannotConvert(field, "short");
    }
    int intValue = PrimitiveDecoders.asIntFromTextBytes(codec, value, pgType, ctx);
    if (intValue < Short.MIN_VALUE || intValue > Short.MAX_VALUE) {
      throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "short", intValue),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return (short) intValue;
  }

  @Pure
  @Override
  public int getInt(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getInt columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return 0; // SQL NULL
    }

    Field field = getFieldWithCodec(columnIndex);
    PgType pgType = field.getPgType();
    PgCodecContext ctx = getCodecContext();

    if (isBinary(columnIndex)) {
      BinaryCodec codec = field.getBinaryCodec();
      if (codec == null) {
        throw cannotConvert(field, "int");
      }
      return PrimitiveDecoders.asInt(codec, value, pgType, ctx);
    }

    // Text format - delegate to codec. BOOL→numeric is handled by BoolCodec
    // which gates on the convertBooleanToNumeric connection property.
    TextCodec codec = field.getTextCodec();
    if (codec == null) {
      throw cannotConvert(field, "int");
    }
    return PrimitiveDecoders.asIntFromTextBytes(codec, value, pgType, ctx);
  }

  @Pure
  @Override
  public long getLong(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getLong columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return 0; // SQL NULL
    }

    Field field = getFieldWithCodec(columnIndex);
    PgType pgType = field.getPgType();
    PgCodecContext ctx = getCodecContext();

    if (isBinary(columnIndex)) {
      BinaryCodec codec = field.getBinaryCodec();
      if (codec == null) {
        throw cannotConvert(field, "long");
      }
      return PrimitiveDecoders.asLong(codec, value, pgType, ctx);
    }

    // Text format - delegate to codec. BOOL→numeric is handled by BoolCodec
    // which gates on the convertBooleanToNumeric connection property.
    TextCodec codec = field.getTextCodec();
    if (codec == null) {
      throw cannotConvert(field, "long");
    }
    return PrimitiveDecoders.asLongFromTextBytes(codec, value, pgType, ctx);
  }

  /**
   * A dummy exception thrown when fast byte[] to number parsing fails and no value can be returned.
   * The exact stack trace does not matter because the exception is always caught and is not visible
   * to users.
   */
  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final NumberFormatException FAST_NUMBER_FAILED = new NumberFormatException() {

    // Override fillInStackTrace to prevent memory leak via Throwable.backtrace hidden field
    // The field is not observable via reflection, however when throwable contains stacktrace, it
    // does
    // hold strong references to user objects (e.g. classes -> classloaders), thus it might lead to
    // OutOfMemory conditions.
    @Override
    public Throwable fillInStackTrace() {
      return this;
    }
  };

  /**
   * Optimised byte[] to number parser. This code does not handle null values, so the caller must do
   * checkResultSet and handle null values prior to calling this function.
   *
   * @param bytes integer represented as a sequence of ASCII bytes
   * @return The parsed number.
   * @throws NumberFormatException If the number is invalid or the out of range for fast parsing.
   *         The value must then be parsed by {@link #toBigDecimal(String, int)}.
   */
  private static BigDecimal getFastBigDecimal(byte[] bytes) throws NumberFormatException {
    if (bytes.length == 0) {
      throw FAST_NUMBER_FAILED;
    }

    int scale = 0;
    long val = 0;
    int start;
    boolean neg;
    if (bytes[0] == '-') {
      neg = true;
      start = 1;
      if (bytes.length == 1 || bytes.length > 19) {
        throw FAST_NUMBER_FAILED;
      }
    } else {
      start = 0;
      neg = false;
      if (bytes.length > 18) {
        throw FAST_NUMBER_FAILED;
      }
    }

    int periodsSeen = 0;
    while (start < bytes.length) {
      byte b = bytes[start++];
      if (b < '0' || b > '9') {
        if (b == '.' && periodsSeen == 0) {
          scale = bytes.length - start;
          periodsSeen++;
          continue;
        } else {
          throw FAST_NUMBER_FAILED;
        }
      }
      val *= 10;
      val += b - '0';
    }

    int numNonSignChars = neg ? bytes.length - 1 : bytes.length;
    if (periodsSeen > 1 || periodsSeen == numNonSignChars) {
      throw FAST_NUMBER_FAILED;
    }

    if (neg) {
      val = -val;
    }

    return BigDecimal.valueOf(val, scale);
  }

  @Pure
  @Override
  public float getFloat(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getFloat columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return 0; // SQL NULL
    }

    Field field = getFieldWithCodec(columnIndex);
    PgType pgType = field.getPgType();
    PgCodecContext ctx = getCodecContext();

    if (isBinary(columnIndex)) {
      BinaryCodec codec = field.getBinaryCodec();
      if (codec == null) {
        throw cannotConvert(field, "float");
      }
      return PrimitiveDecoders.asFloat(codec, value, pgType, ctx);
    }

    // Text format - delegate to codec. BOOL→numeric is handled by BoolCodec
    // which gates on the convertBooleanToNumeric connection property.
    TextCodec codec = field.getTextCodec();
    if (codec == null) {
      throw cannotConvert(field, "float");
    }
    return PrimitiveDecoders.asFloat(codec,castNonNull(getFixedString(columnIndex)), pgType, ctx);
  }

  @Pure
  @Override
  public double getDouble(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getDouble columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return 0; // SQL NULL
    }

    Field field = getFieldWithCodec(columnIndex);
    PgType pgType = field.getPgType();
    PgCodecContext ctx = getCodecContext();

    if (isBinary(columnIndex)) {
      BinaryCodec codec = field.getBinaryCodec();
      if (codec == null) {
        throw cannotConvert(field, "double");
      }
      return PrimitiveDecoders.asDouble(codec, value, pgType, ctx);
    }

    // Text format - delegate to codec. BOOL→numeric is handled by BoolCodec
    // which gates on the convertBooleanToNumeric connection property.
    TextCodec codec = field.getTextCodec();
    if (codec == null) {
      throw cannotConvert(field, "double");
    }
    return PrimitiveDecoders.asDouble(codec,castNonNull(getFixedString(columnIndex)), pgType, ctx);
  }

  @Override
  @SuppressWarnings("deprecation")
  public @Nullable BigDecimal getBigDecimal(
      int columnIndex, int scale) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getBigDecimal columnIndex: {0}", columnIndex);
    return (BigDecimal) getNumeric(columnIndex, scale, false);
  }

  @Pure
  private @Nullable Number getNumeric(
      int columnIndex, @Nullable Integer scale, boolean allowSpecial) throws SQLException {
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return null;
    }

    if (isBinary(columnIndex)) {
      Field field = getFieldWithCodec(columnIndex);
      BinaryCodec codec = field.getBinaryCodec();
      if (codec != null) {
        if (allowSpecial) {
          // For getNumeric with allowSpecial (called from internalGetObject with NUMERIC type),
          // use decodeBinary to get the Number directly (may return Double for NaN/Infinity)
          TypeDescriptor type = field.getPgType();
          CodecContext ctx = getCodecContext();
          Object decoded = codec.decodeBinary(value, 0, value.length, type, ctx);
          if (decoded instanceof Number) {
            Number num = (Number) decoded;
            if (num instanceof BigDecimal) {
              return scaleBigDecimal((BigDecimal) num, scale);
            }
            return num;
          }
          return null;
        }
        TypeDescriptor type = field.getPgType();
        CodecContext ctx = getCodecContext();
        BigDecimal bd = codec.decodeAsBigDecimal(value, 0, value.length, type, ctx);
        if (bd != null) {
          bd = scaleBigDecimal(bd, scale);
        }
        return bd;
      }
      throw new PSQLException(GT.tr("No codec for binary numeric conversion"),
          PSQLState.DATA_TYPE_MISMATCH);
    }

    Encoding encoding = connection.getEncoding();
    if (encoding.hasAsciiNumbers()) {
      try {
        BigDecimal res = getFastBigDecimal(value);
        res = scaleBigDecimal(res, scale);
        return res;
      } catch (NumberFormatException ignore) {
        // Fast conversion to BigDecimal failed, try slower approach below
      }
    }

    String stringValue = castNonNull(getFixedString(columnIndex));

    // BOOL→numeric conversion is handled by BoolCodec, which gates on the
    // convertBooleanToNumeric connection property and throws otherwise.
    if (fields[columnIndex - 1].getOID() == Oid.BOOL) {
      Field field = getFieldWithCodec(columnIndex);
      TextCodec codec = field.getTextCodec();
      if (codec != null) {
        BigDecimal bd = codec.decodeAsBigDecimal(stringValue, field.getPgType(), getCodecContext());
        if (bd != null) {
          return scaleBigDecimal(bd, scale);
        }
      }
    }

    if (allowSpecial) {
      if ("NaN".equalsIgnoreCase(stringValue)) {
        return Double.NaN;
      } else if ("Infinity".equalsIgnoreCase(stringValue)) {
        return Double.POSITIVE_INFINITY;
      } else if ("-Infinity".equalsIgnoreCase(stringValue)) {
        return Double.NEGATIVE_INFINITY;
      }
    }
    return toBigDecimal(stringValue, scale);
  }

  /**
   * {@inheritDoc}
   *
   * <p>In normal use, the bytes represent the raw values returned by the backend. However, if the
   * column is an OID, then it is assumed to refer to a Large Object, and that object is returned as
   * a byte array.</p>
   *
   * <p><b>Be warned</b> If the large object is huge, then you may run out of memory.</p>
   */
  @Pure
  @Override
  public byte @Nullable [] getBytes(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getBytes columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return null;
    }

    if (isBinary(columnIndex)) {
      // The data is already binary; still honour maxFieldSize, which applies to
      // char/varchar/binary columns regardless of transfer format. trimBytes is a
      // no-op for non-trimmable types (e.g. a binary int4), so this is safe.
      return trimBytes(columnIndex, value);
    }
    if (fields[columnIndex - 1].getOID() == Oid.BYTEA) {
      return trimBytes(columnIndex, PGbytea.toBytes(value));
    } else {
      return trimBytes(columnIndex, value);
    }
  }

  @Override
  @Pure
  public @Nullable Date getDate(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getDate columnIndex: {0}", columnIndex);
    return getDate(columnIndex, null);
  }

  @Override
  @Pure
  public @Nullable Time getTime(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getTime columnIndex: {0}", columnIndex);
    return getTime(columnIndex, null);
  }

  @Override
  @Pure
  public @Nullable Timestamp getTimestamp(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getTimestamp columnIndex: {0}", columnIndex);
    return getTimestamp(columnIndex, null);
  }

  @Override
  @Pure
  public @Nullable InputStream getAsciiStream(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getAsciiStream columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return null;
    }

    // Version 7.2 supports AsciiStream for all the PG text types
    // As the spec/javadoc for this method indicate this is to be used for
    // large text values (i.e. LONGVARCHAR) PG doesn't have a separate
    // long string datatype, but with toast the text datatype is capable of
    // handling very large values. Thus the implementation ends up calling
    // getString() since there is no current way to stream the value from the server
    String stringValue = castNonNull(getString(columnIndex));
    return new ByteArrayInputStream(stringValue.getBytes(StandardCharsets.US_ASCII));
  }

  @Override
  @Pure
  @SuppressWarnings("deprecation")
  public @Nullable InputStream getUnicodeStream(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getUnicodeStream columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return null;
    }

    // Version 7.2 supports AsciiStream for all the PG text types
    // As the spec/javadoc for this method indicate this is to be used for
    // large text values (i.e. LONGVARCHAR) PG doesn't have a separate
    // long string datatype, but with toast the text datatype is capable of
    // handling very large values. Thus the implementation ends up calling
    // getString() since there is no current way to stream the value from the server
    String stringValue = castNonNull(getString(columnIndex));
    return new ByteArrayInputStream(stringValue.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  @Pure
  public @Nullable InputStream getBinaryStream(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getBinaryStream columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return null;
    }

    // Version 7.2 supports BinaryStream for all PG bytea type
    // As the spec/javadoc for this method indicate this is to be used for
    // large binary values (i.e. LONGVARBINARY) PG doesn't have a separate
    // long binary datatype, but with toast the bytea datatype is capable of
    // handling very large values. Thus the implementation ends up calling
    // getBytes() since there is no current way to stream the value from the server
    byte[] b = getBytes(columnIndex);
    if (b != null) {
      return new ByteArrayInputStream(b);
    }
    return null;
  }

  @Override
  @Pure
  public @Nullable String getString(String columnName) throws SQLException {
    return getString(findColumn(columnName));
  }

  @Pure
  @Override
  public boolean getBoolean(String columnName) throws SQLException {
    return getBoolean(findColumn(columnName));
  }

  @Override
  @Pure
  public byte getByte(String columnName) throws SQLException {
    return getByte(findColumn(columnName));
  }

  @Override
  @Pure
  public short getShort(String columnName) throws SQLException {
    return getShort(findColumn(columnName));
  }

  @Override
  @Pure
  public int getInt(String columnName) throws SQLException {
    return getInt(findColumn(columnName));
  }

  @Override
  @Pure
  public long getLong(String columnName) throws SQLException {
    return getLong(findColumn(columnName));
  }

  @Override
  @Pure
  public float getFloat(String columnName) throws SQLException {
    return getFloat(findColumn(columnName));
  }

  @Override
  @Pure
  public double getDouble(String columnName) throws SQLException {
    return getDouble(findColumn(columnName));
  }

  @Override
  @Pure
  @SuppressWarnings("deprecation")
  public @Nullable BigDecimal getBigDecimal(String columnName, int scale) throws SQLException {
    return getBigDecimal(findColumn(columnName), scale);
  }

  @Override
  @Pure
  public byte @Nullable [] getBytes(String columnName) throws SQLException {
    return getBytes(findColumn(columnName));
  }

  @Override
  @Pure
  public @Nullable Date getDate(String columnName) throws SQLException {
    return getDate(findColumn(columnName), null);
  }

  @Override
  @Pure
  public @Nullable Time getTime(String columnName) throws SQLException {
    return getTime(findColumn(columnName), null);
  }

  @Override
  @Pure
  public @Nullable Timestamp getTimestamp(String columnName) throws SQLException {
    return getTimestamp(findColumn(columnName), null);
  }

  @Override
  @Pure
  public @Nullable InputStream getAsciiStream(String columnName) throws SQLException {
    return getAsciiStream(findColumn(columnName));
  }

  @Override
  @Pure
  @SuppressWarnings("deprecation")
  public @Nullable InputStream getUnicodeStream(String columnName) throws SQLException {
    return getUnicodeStream(findColumn(columnName));
  }

  @Override
  @Pure
  public @Nullable InputStream getBinaryStream(String columnName) throws SQLException {
    return getBinaryStream(findColumn(columnName));
  }

  @Override
  @Pure
  public @Nullable SQLWarning getWarnings() throws SQLException {
    checkClosed();
    return warnings;
  }

  @Override
  public void clearWarnings() throws SQLException {
    checkClosed();
    warnings = null;
  }

  protected void addWarning(SQLWarning warnings) {
    if (this.warnings != null) {
      this.warnings.setNextWarning(warnings);
    } else {
      this.warnings = warnings;
    }
  }

  @Override
  public @Nullable String getCursorName() throws SQLException {
    checkClosed();
    return null;
  }

  @Override
  public @Nullable Object getObject(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getObject columnIndex: {0}", columnIndex);

    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return null;
    }

    Field field = fields[columnIndex - 1];

    // some fields can be null, mainly from those returned by MetaData methods
    if (field == null) {
      wasNullFlag = true;
      return null;
    }

    int oid = field.getOID();

    // Special case: bit(1) returns Boolean, wider bit returns PGobject (via BitCodec), for backward
    // compatibility. The bit count is the byte length in text but the leading int4 in binary, so it
    // must be read format-aware before deciding; bit(n>1) then falls through to the codec path.
    if (oid == Oid.BIT) {
      int nbits = isBinary(columnIndex) ? ByteConverter.int4(value, 0) : value.length;
      if (nbits == 1) {
        return getBoolean(columnIndex);
      }
      // bit(n>1) — fall through to the codec path, which returns a PGobject.
    }
    // isBinary()/getBoolean() above are instance calls, so the checker can no longer prove thisRow
    // is non-null after the bit branch merges back in; getRawValue() above did set it.
    castNonNull(thisRow, "thisRow");

    // Special case: refcursor returns a ResultSet
    if (oid == Oid.REFCURSOR) {
      return internalGetObject(columnIndex, field);
    }

    // Special case: numeric/decimal must be rescaled to the column's typmod scale
    // (e.g. a negative scale from numeric(2,-2), PostgreSQL 15+) — the codec path below
    // decodes the wire value as-is and knows nothing about the column's typmod.
    if (oid == Oid.NUMERIC) {
      return internalGetObject(columnIndex, field);
    }

    // Special case: XML returns SQLXML wrapper for the JDBC contract; the
    // codec layer otherwise hands back String.
    if (oid == Oid.XML) {
      return getSQLXML(columnIndex);
    }

    // Special case: 'unknown' (oid 705) — the backend couldn't infer the
    // column type (e.g. `SELECT 'ok' where ...` with no cast). The legacy
    // contract is to coerce the raw bytes to a String rather than falling
    // through to the codec layer (which would return a PGobject wrapper).
    if (oid == 705) {
      return getString(columnIndex);
    }

    // For date/time columns, the legacy contract is that any getter triggers
    // the per-resultset default-timezone cache (so subsequent getters with
    // the same Calendar hit a hot path). Touch the cache here since the
    // codec path doesn't go through getDefaultCalendar otherwise.
    if (oid == Oid.DATE || oid == Oid.TIME || oid == Oid.TIMETZ
        || oid == Oid.TIMESTAMP || oid == Oid.TIMESTAMPTZ) {
      dateTimeHelper.getDefaultCalendar();
    }

    // Use codec for all other types
    field = getFieldWithCodec(columnIndex);
    PgType pgType = field.getPgType();
    PgCodecContext ctx = getCodecContext();

    // Honor the connection-level type map: if the user has registered a Java
    // class for this PostgreSQL type, route through decodeBinaryAs/decodeTextAs
    // so SQLData (and PGobject subclass) mappings take effect on plain getObject().
    Class<?> mapped = ctx.getMappedClass(pgType.getFullName());
    if (mapped == null) {
      mapped = ctx.getMappedClass(pgType.getTypeName().getName());
    }

    if (isBinary(columnIndex)) {
      BinaryCodec codec = field.getBinaryCodec();
      if (codec != null) {
        if (mapped != null) {
          return codec.decodeBinaryAs(value, 0, value.length, pgType, mapped, ctx);
        }
        return codec.decodeBinary(value, 0, value.length, pgType, ctx);
      }
      // No binary codec — fall back to legacy Connection.addDataType() lookup.
      // Decoding binary bytes via a TextCodec is wrong (different wire formats),
      // so we never cross over from binary to text here.
      return connection.getObject(getPGType(columnIndex), null, value);
    }

    // Text format
    TextCodec codec = field.getTextCodec();
    if (codec != null) {
      String stringValue = castNonNull(getString(columnIndex));
      if (mapped != null) {
        return codec.decodeTextAs(stringValue, pgType, mapped, ctx);
      }
      return codec.decodeText(stringValue, pgType, ctx);
    }
    // No text codec — legacy Connection.addDataType() lookup.
    String stringValue = castNonNull(getString(columnIndex));
    return connection.getObject(getPGType(columnIndex), stringValue, null);
  }

  @Override
  public @Nullable Object getObject(String columnName) throws SQLException {
    return getObject(findColumn(columnName));
  }

  @Override
  public @NonNegative int findColumn(String columnName) throws SQLException {
    checkClosed();

    int col = findColumnIndex(columnName);
    if (col == 0) {
      throw new PSQLException(
          GT.tr("The column name {0} was not found in this ResultSet.", columnName),
          PSQLState.UNDEFINED_COLUMN);
    }
    return col;
  }

  public static Map<String, Integer> createColumnNameIndexMap(Field[] fields,
      boolean isSanitiserDisabled) {
    Map<String, Integer> columnNameIndexMap = new HashMap<>(fields.length * 2);
    // The JDBC spec says when you have duplicate columns names,
    // the first one should be returned. So load the map in
    // reverse order so the first ones will overwrite later ones.
    for (int i = fields.length - 1; i >= 0; i--) {
      String columnLabel = fields[i].getColumnLabel();
      if (isSanitiserDisabled) {
        columnNameIndexMap.put(columnLabel, i + 1);
      } else {
        columnNameIndexMap.put(columnLabel.toLowerCase(Locale.US), i + 1);
      }
    }
    return columnNameIndexMap;
  }

  private @NonNegative int findColumnIndex(String columnName) {
    if (columnNameIndexMap == null) {
      if (originalQuery != null) {
        columnNameIndexMap = originalQuery.getResultSetColumnNameIndexMap();
      }
      if (columnNameIndexMap == null) {
        columnNameIndexMap = createColumnNameIndexMap(fields, connection.isColumnSanitiserDisabled());
      }
    }

    Integer index = columnNameIndexMap.get(columnName);
    if (index != null) {
      return index;
    }

    index = columnNameIndexMap.get(columnName.toLowerCase(Locale.US));
    if (index != null) {
      columnNameIndexMap.put(columnName, index);
      return index;
    }

    index = columnNameIndexMap.get(columnName.toUpperCase(Locale.US));
    if (index != null) {
      columnNameIndexMap.put(columnName, index);
      return index;
    }

    return 0;
  }

  /**
   * Returns the OID of a field. It is used internally by the driver.
   *
   * @param field field index
   * @return OID of a field
   */
  public int getColumnOID(int field) {
    return fields[field - 1].getOID();
  }

  /**
   * This is used to fix get*() methods on Money fields. It should only be used by those methods!
   *
   * <p>It converts ($##.##) to -##.## and $##.## to ##.##</p>
   *
   * @param col column position (1-based)
   * @return numeric-parsable representation of money string literal
   * @throws SQLException if something wrong happens
   */
  public @Nullable String getFixedString(int col) throws SQLException {
    String stringValue = castNonNull(getString(col));
    return trimMoney(stringValue);
  }

  private static @PolyNull String trimMoney(@PolyNull String s) {
    if (s == null) {
      return null;
    }

    // if we don't have at least 2 characters it can't be money.
    if (s.length() < 2) {
      return s;
    }

    // Handle Money
    char ch = s.charAt(0);

    // optimise for non-money type: return immediately with one check
    // if the first char cannot be '(', '$' or '-'
    if (ch > '-') {
      return s;
    }

    if (ch == '(') {
      s = "-" + PGtokenizer.removePara(s).substring(1);
    } else if (ch == '$') {
      s = s.substring(1);
    } else if (ch == '-' && s.charAt(1) == '$') {
      s = "-" + s.substring(2);
    }

    return s;
  }

  @Pure
  protected String getPGType(@Positive int column) throws SQLException {
    Field field = getFieldWithType(column);
    return field.getPGType();
  }

  @Pure
  protected int getSQLType(@Positive int column) throws SQLException {
    Field field = getFieldWithType(column);
    return field.getSQLType();
  }

  private Field getFieldWithType(@Positive int column) throws SQLException {
    Field field = fields[column - 1];
    field.initializePgType(connection.getTypeInfo());
    return field;
  }

  /**
   * Gets the field with both PgType and Codec initialized.
   * This method should be used when codec access is needed.
   *
   * @param column the 1-based column index
   * @return the field with initialized type and codec
   * @throws SQLException if an error occurs
   */
  private Field getFieldWithCodec(@Positive int column) throws SQLException {
    Field field = fields[column - 1];
    TypeInfo typeInfo = connection.getTypeInfo();
    field.initializePgType(typeInfo);
    field.initializeCodec(typeInfo.getCodecRegistry());
    return field;
  }

  /**
   * Decodes the value at the given column using the codec's decodeBinary/decodeText.
   * The codec's decode methods respect java.time preferences, so this is suitable
   * for internalGetObject dispatch where the right return type depends on connection properties.
   */
  private @Nullable Object decodeViaCodec(@Positive int columnIndex) throws SQLException {
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return null;
    }
    Field field = getFieldWithCodec(columnIndex);
    PgType pgType = field.getPgType();
    PgCodecContext ctx = getCodecContext();
    if (isBinary(columnIndex)) {
      BinaryCodec codec = field.getBinaryCodec();
      if (codec != null) {
        return codec.decodeBinary(value, 0, value.length, pgType, ctx);
      }
    } else {
      TextCodec codec = field.getTextCodec();
      if (codec != null) {
        return codec.decodeText(castNonNull(getString(columnIndex)), pgType, ctx);
      }
    }
    // CodecRegistry guarantees a codec for every OID (FallbackCodec for unknown types),
    // so this branch is unreachable. Throwing protects the invariant: returning null
    // would silently surface as SQL NULL to the caller.
    throw new PSQLException(
        GT.tr("No {0} codec available for type {1}",
            isBinary(columnIndex) ? "binary" : "text", pgType.getTypeName()),
        PSQLState.SYSTEM_ERROR);
  }

  @EnsuresNonNull({"updateValues", "rows"})
  private void checkUpdateable() throws SQLException {
    checkClosed();

    if (!isUpdateable()) {
      throw new PSQLException(
          GT.tr(
              "ResultSet is not updateable.  The query that generated this result set must select only one table, and must select all primary keys from that table. See the JDBC 2.1 API Specification, section 5.6 for more details."),
          PSQLState.INVALID_CURSOR_STATE);
    }

    if (updateValues == null) {
      // allow every column to be updated without a rehash.
      updateValues = new HashMap<>((int) (fields.length / 0.75), 0.75f);
    }
    castNonNull(updateValues, "updateValues");
    castNonNull(rows, "rows");
  }

  @Pure
  @EnsuresNonNull("rows")
  protected void checkClosed() throws SQLException {
    if (rows == null) {
      throw new PSQLException(GT.tr("This ResultSet is closed."), PSQLState.OBJECT_NOT_IN_STATE);
    }
  }

  /*
   * for jdbc3 to call internally
   */
  protected boolean isResultSetClosed() {
    return rows == null;
  }

  @Pure
  protected void checkColumnIndex(@Positive int column) throws SQLException {
    if (column < 1 || column > fields.length) {
      throw new PSQLException(
          GT.tr("The column index is out of range: {0}, number of columns: {1}.",
              column, fields.length),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
  }

  /**
   * Checks that the result set is not closed, it's positioned on a valid row and that the given
   * column number is valid. Also updates the {@link #wasNullFlag} to correct value.
   *
   * @param column The column number to check. Range starts from 1.
   * @return raw value or null
   * @throws SQLException If state or column is invalid.
   */
  @EnsuresNonNull("thisRow")
  protected byte @Nullable [] getRawValue(@Positive int column) throws SQLException {
    checkClosed();
    if (thisRow == null) {
      throw new PSQLException(
          GT.tr("ResultSet not positioned properly, perhaps you need to call next."),
          PSQLState.INVALID_CURSOR_STATE);
    }
    checkColumnIndex(column);
    byte[] bytes = thisRow.get(column - 1);
    wasNullFlag = bytes == null;
    return bytes;
  }

  /**
   * Returns true if the value of the given column is in binary format.
   *
   * @param column The column to check. Range starts from 1.
   * @return True if the column is in binary format.
   */
  @Pure
  protected boolean isBinary(@Positive int column) {
    return fields[column - 1].getFormat() == Field.BINARY_FORMAT;
  }

  // ----------------- Formatting Methods -------------------

  private static final BigInteger SHORTMAX = new BigInteger(Short.toString(Short.MAX_VALUE));
  private static final BigInteger SHORTMIN = new BigInteger(Short.toString(Short.MIN_VALUE));

  public static short toShort(@Nullable String s) throws SQLException {
    if (s != null) {
      try {
        s = s.trim();
        return Short.parseShort(s);
      } catch (NumberFormatException e) {
        try {
          BigDecimal n = new BigDecimal(s);
          BigInteger i = n.toBigInteger();
          int gt = i.compareTo(SHORTMAX);
          int lt = i.compareTo(SHORTMIN);

          if (gt > 0 || lt < 0) {
            throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "short", s),
                PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
          }
          return i.shortValue();

        } catch (NumberFormatException ne) {
          throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "short", s),
              PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
        }
      }
    }
    return 0; // SQL NULL
  }

  private static final BigInteger INTMAX = new BigInteger(Integer.toString(Integer.MAX_VALUE));
  private static final BigInteger INTMIN = new BigInteger(Integer.toString(Integer.MIN_VALUE));

  public static int toInt(@Nullable String s) throws SQLException {
    if (s != null) {
      try {
        s = s.trim();
        return Integer.parseInt(s);
      } catch (NumberFormatException e) {
        try {
          BigDecimal n = new BigDecimal(s);
          BigInteger i = n.toBigInteger();

          int gt = i.compareTo(INTMAX);
          int lt = i.compareTo(INTMIN);

          if (gt > 0 || lt < 0) {
            throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "int", s),
                PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
          }
          return i.intValue();

        } catch (NumberFormatException ne) {
          throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "int", s),
              PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
        }
      }
    }
    return 0; // SQL NULL
  }

  private static final BigInteger LONGMAX = new BigInteger(Long.toString(Long.MAX_VALUE));
  private static final BigInteger LONGMIN = new BigInteger(Long.toString(Long.MIN_VALUE));

  public static long toLong(@Nullable String s) throws SQLException {
    if (s != null) {
      try {
        s = s.trim();
        return Long.parseLong(s);
      } catch (NumberFormatException e) {
        try {
          BigDecimal n = new BigDecimal(s);
          BigInteger i = n.toBigInteger();
          int gt = i.compareTo(LONGMAX);
          int lt = i.compareTo(LONGMIN);

          if (gt > 0 || lt < 0) {
            throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "long", s),
                PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
          }
          return i.longValue();
        } catch (NumberFormatException ne) {
          throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "long", s),
              PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
        }
      }
    }
    return 0; // SQL NULL
  }

  public static @PolyNull BigDecimal toBigDecimal(@PolyNull String s) throws SQLException {
    if (s == null) {
      return null;
    }
    try {
      s = s.trim();
      return new BigDecimal(s);
    } catch (NumberFormatException e) {
      throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "BigDecimal", s),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
  }

  public static @PolyNull BigDecimal toBigDecimal(@PolyNull String s, int scale) throws SQLException {
    return toBigDecimal(s, (Integer) scale);
  }

  private static @PolyNull BigDecimal toBigDecimal(@PolyNull String s,
      @Nullable Integer scale) throws SQLException {
    if (s == null) {
      return null;
    }
    BigDecimal val = toBigDecimal(s);
    return scaleBigDecimal(val, scale);
  }

  /**
   * Extracts the scale of a {@code numeric} column from its type modifier. Since PostgreSQL 15 the
   * scale is a signed 11-bit value (e.g. {@code numeric(2,-2)}), so it must be sign-extended rather
   * than masked with {@code 0xffff}.
   *
   * @param typmod the column type modifier, which must not be {@code -1}
   * @return the (possibly negative) scale
   */
  private static int decodeNumericScale(int typmod) {
    return ((((typmod - 4) & 0x7ff) ^ 0x400) - 0x400);
  }

  private static BigDecimal scaleBigDecimal(BigDecimal val, @Nullable Integer scale)
      throws PSQLException {
    if (scale == null) {
      return val;
    }
    try {
      return val.setScale(scale, RoundingMode.HALF_EVEN);
    } catch (ArithmeticException e) {
      throw new PSQLException(
          GT.tr("Bad value for type {0} : {1}", "BigDecimal", val),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
  }

  public static float toFloat(@Nullable String s) throws SQLException {
    if (s != null) {
      try {
        s = s.trim();
        return Float.parseFloat(s);
      } catch (NumberFormatException e) {
        throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "float", s),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
    }
    return 0; // SQL NULL
  }

  public static double toDouble(@Nullable String s) throws SQLException {
    if (s != null) {
      try {
        s = s.trim();
        return Double.parseDouble(s);
      } catch (NumberFormatException e) {
        throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "double", s),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
    }
    return 0; // SQL NULL
  }

  @RequiresNonNull("rows")
  private void initRowBuffer() {
    thisRow = castNonNull(rows, "rows").get(currentRow);
    // We only need a copy of the current row if we're going to
    // modify it via an updatable resultset.
    if (resultsetconcurrency == ResultSet.CONCUR_UPDATABLE) {
      rowBuffer = thisRow.updateableCopy();
    } else {
      rowBuffer = null;
    }
  }

  private boolean isColumnTrimmable(@Positive int columnIndex) throws SQLException {
    switch (getSQLType(columnIndex)) {
      case Types.CHAR:
      case Types.VARCHAR:
      case Types.LONGVARCHAR:
      case Types.BINARY:
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
        return true;
    }
    return false;
  }

  private byte[] trimBytes(@Positive int columnIndex, byte[] bytes) throws SQLException {
    // we need to trim if maxsize is set and the length is greater than maxsize and the
    // type of this column is a candidate for trimming
    if (maxFieldSize > 0 && bytes.length > maxFieldSize && isColumnTrimmable(columnIndex)) {
      byte[] newBytes = new byte[maxFieldSize];
      System.arraycopy(bytes, 0, newBytes, 0, maxFieldSize);
      return newBytes;
    } else {
      return bytes;
    }
  }

  private String trimString(@Positive int columnIndex, String string) throws SQLException {
    // we need to trim if maxsize is set and the length is greater than maxsize and the
    // type of this column is a candidate for trimming
    if (maxFieldSize > 0 && string.length() > maxFieldSize && isColumnTrimmable(columnIndex)) {
      return string.substring(0, maxFieldSize);
    } else {
      return string;
    }
  }

  private static PSQLException cannotConvert(Field field, String targetType) {
    return new PSQLException(
        GT.tr("Cannot convert the column of type {0} to requested type {1}.",
            Oid.toString(field.getOID()), targetType),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  protected void updateValue(@Positive int columnIndex, @Nullable Object value) throws SQLException {
    checkUpdateable();

    if (!onInsertRow && (isBeforeFirst() || isAfterLast() || castNonNull(rows, "rows").isEmpty())) {
      throw new PSQLException(
          GT.tr(
              "Cannot update the ResultSet because it is either before the start or after the end of the results."),
          PSQLState.INVALID_CURSOR_STATE);
    }

    checkColumnIndex(columnIndex);

    doingUpdates = !onInsertRow;
    if (value == null) {
      updateNull(columnIndex);
    } else {
      PGResultSetMetaData md = (PGResultSetMetaData) getMetaData();
      castNonNull(updateValues, "updateValues")
          .put(md.getBaseColumnName(columnIndex), value);
    }
  }

  /**
   * Like {@link #updateValue(int, Object)}, but also records {@code targetSqlType} so
   * {@link #insertRow()} and {@link #updateRow()} bind the value through
   * {@link PreparedStatement#setObject(int, Object, SQLType)} instead of the type-inferring
   * {@link PreparedStatement#setObject(int, Object)}. Unlike {@link #updateValue(int, Object)},
   * a {@code null} value is stored as-is rather than routed through {@link #updateNull(int)}:
   * the caller-supplied type already pins the OID a {@code null} should be bound with, so the
   * generic {@code NullObject} cast is unnecessary here.
   */
  protected void updateValue(@Positive int columnIndex, @Nullable Object value, SQLType targetSqlType)
      throws SQLException {
    checkUpdateable();

    if (!onInsertRow && (isBeforeFirst() || isAfterLast() || castNonNull(rows, "rows").isEmpty())) {
      throw new PSQLException(
          GT.tr(
              "Cannot update the ResultSet because it is either before the start or after the end of the results."),
          PSQLState.INVALID_CURSOR_STATE);
    }

    checkColumnIndex(columnIndex);

    doingUpdates = !onInsertRow;
    PGResultSetMetaData md = (PGResultSetMetaData) getMetaData();
    String columnName = md.getBaseColumnName(columnIndex);
    castNonNull(updateValues, "updateValues").put(columnName, value);

    HashMap<String, SQLType> sqlTypes = updateSqlTypes;
    if (sqlTypes == null) {
      sqlTypes = new HashMap<>();
      updateSqlTypes = sqlTypes;
    }
    sqlTypes.put(columnName, targetSqlType);
  }

  @Pure
  protected Object getUUID(String data) throws SQLException {
    UUID uuid;
    try {
      uuid = UUID.fromString(data);
    } catch (IllegalArgumentException iae) {
      throw new PSQLException(GT.tr("Invalid UUID data."), PSQLState.INVALID_PARAMETER_VALUE, iae);
    }

    return uuid;
  }

  @Pure
  protected Object getUUID(byte[] data) throws SQLException {
    return new UUID(ByteConverter.int8(data, 0), ByteConverter.int8(data, 8));
  }

  private class PrimaryKey {
    int index; // where in the result set is this primaryKey
    String name; // what is the columnName of this primary Key

    PrimaryKey(int index, String name) {
      this.index = index;
      this.name = name;
    }

    @Nullable Object getValue() throws SQLException {
      return getObject(index);
    }
  }

  //
  // We need to specify the type of NULL when updating a column to NULL, so
  // NullObject is a simple extension of PGobject that always returns null
  // values but retains column type info.
  //

  static class NullObject extends PGobject {
    NullObject(String type) {
      this.type = type;
    }

    @Override
    public @Nullable String getValue() {
      return null;
    }
  }

  /**
   * Used to add rows to an already existing ResultSet that exactly match the existing rows.
   * Currently only used for assembling generated keys from batch statement execution.
   */
  void addRows(List<Tuple> tuples) {
    castNonNull(rows, "rows").addAll(tuples);
  }

  @Override
  public void updateRef(@Positive int columnIndex, @Nullable Ref x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "updateRef(int,Ref)");
  }

  @Override
  public void updateRef(String columnName, @Nullable Ref x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "updateRef(String,Ref)");
  }

  @Override
  public void updateBlob(@Positive int columnIndex, @Nullable Blob x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "updateBlob(int,Blob)");
  }

  @Override
  public void updateBlob(String columnName, @Nullable Blob x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "updateBlob(String,Blob)");
  }

  @Override
  public void updateClob(@Positive int columnIndex, @Nullable Clob x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "updateClob(int,Clob)");
  }

  @Override
  public void updateClob(String columnName, @Nullable Clob x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "updateClob(String,Clob)");
  }

  @Override
  public void updateArray(@Positive int columnIndex, @Nullable Array x) throws SQLException {
    updateObject(columnIndex, x);
  }

  @Override
  public void updateArray(String columnName, @Nullable Array x) throws SQLException {
    updateArray(findColumn(columnName), x);
  }

  @Override
  public <T> @Nullable T getObject(@Positive int columnIndex, Class<T> type) throws SQLException {
    if (type == null) {
      throw new SQLException("type is null");
    }
    int sqlType = getSQLType(columnIndex);

    // Handle JDBC-specific types that codecs don't manage
    if (type == Calendar.class) {
      if (sqlType == Types.TIMESTAMP || sqlType == Types.TIMESTAMP_WITH_TIMEZONE) {
        Timestamp timestampValue = getTimestamp(columnIndex);
        if (timestampValue == null) {
          return null;
        }
        Calendar calendar = Calendar.getInstance(getDefaultCalendar().getTimeZone());
        calendar.setTimeInMillis(timestampValue.getTime());
        return type.cast(calendar);
      }
      throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
          PSQLState.INVALID_PARAMETER_VALUE);
    } else if (type == Blob.class) {
      if (sqlType == Types.BLOB || sqlType == Types.BINARY || sqlType == Types.BIGINT) {
        return type.cast(getBlob(columnIndex));
      }
      throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
          PSQLState.INVALID_PARAMETER_VALUE);
    } else if (type == Clob.class) {
      if (sqlType == Types.CLOB || sqlType == Types.BIGINT) {
        return type.cast(getClob(columnIndex));
      }
      throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
          PSQLState.INVALID_PARAMETER_VALUE);
    } else if (type == SQLXML.class) {
      if (sqlType == Types.SQLXML) {
        return type.cast(getSQLXML(columnIndex));
      }
      throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
          PSQLState.INVALID_PARAMETER_VALUE);
    } else if (type == InetAddress.class) {
      String inetText = getString(columnIndex);
      if (inetText == null) {
        return null;
      }
      int slash = inetText.indexOf("/");
      try {
        return type.cast(InetAddress.getByName(slash < 0 ? inetText : inetText.substring(0, slash)));
      } catch (UnknownHostException ex) {
        throw new PSQLException(GT.tr("Invalid Inet data."), PSQLState.INVALID_PARAMETER_VALUE, ex);
      }
    } else if (PGobject.class.isAssignableFrom(type)
        && (type == PGobject.class
            || connection.getTypeInfo().getPGobject(getPGType(columnIndex)) != null)) {
      // connection.getObject owns the bare PGobject.class request and any type with a
      // registered PGobject subclass (the geometric types, money, interval, and user types
      // added through addDataType): it materialises a typed PGobject even for an SQL NULL and
      // honours the registered subclass. Codec-managed PGobject subclasses such as PGRange and
      // PGmultirange are not registered there, so they fall through to the codec path below and
      // decode through decodeBinaryAs/decodeTextAs.
      Object object;
      Class<? extends PGobject> registered =
          connection.getTypeInfo().getPGobject(getPGType(columnIndex));
      if (isBinary(columnIndex) && registered != null
          && PGBinaryObject.class.isAssignableFrom(registered)) {
        // Only a PGBinaryObject subclass parses the binary wire directly; connection.getObject
        // consumes the bytes for it and ignores the string.
        byte[] byteValue = castNonNull(thisRow, "thisRow").get(columnIndex - 1);
        object = connection.getObject(getPGType(columnIndex), null, byteValue);
      } else {
        // Otherwise -- text, an unregistered type, or a registered non-binary PGobject subclass --
        // the value is the column's text, so decode the wire to text through the codec (getString).
        // Passing the raw bytes would make connection.getObject call setValue(null) and drop the
        // value under binary receive (e.g. refcursor, or an addDataType subclass that is not a
        // PGBinaryObject).
        object = connection.getObject(getPGType(columnIndex), getString(columnIndex), null);
      }
      return type.cast(object);
    }

    // Delegate to codec for all value types
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return null;
    }

    Field field = getFieldWithCodec(columnIndex);
    PgType pgType = field.getPgType();
    PgCodecContext ctx = getCodecContext();

    if (isBinary(columnIndex)) {
      BinaryCodec codec = field.getBinaryCodec();
      if (codec != null) {
        return codec.decodeBinaryAs(value, 0, value.length, pgType, type, ctx);
      }
    } else {
      TextCodec codec = field.getTextCodec();
      if (codec != null) {
        String stringValue = castNonNull(getString(columnIndex));
        return codec.decodeTextAs(stringValue, pgType, type, ctx);
      }
    }

    throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
        PSQLState.INVALID_PARAMETER_VALUE);
  }

  @Override
  public <T> @Nullable T getObject(String columnLabel, Class<T> type) throws SQLException {
    return getObject(findColumn(columnLabel), type);
  }

  @Override
  public @Nullable Object getObject(String s, @Nullable Map<String, Class<?>> map) throws SQLException {
    return getObjectImpl(s, map);
  }

  @Override
  public @Nullable Object getObject(@Positive int i, @Nullable Map<String, Class<?>> map) throws SQLException {
    return getObjectImpl(i, map);
  }

  @Override
  public void updateObject(@Positive int columnIndex, @Nullable Object x, SQLType targetSqlType,
      int scaleOrLength) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      // scaleOrLength is unused, same as updateObject(int, Object, int): the eventual bind goes
      // through PreparedStatement#setObject(int, Object, SQLType), and this class has no lower-level
      // hook to plumb a scale into.
      updateValue(columnIndex, x, targetSqlType);
    }
  }

  @Override
  public void updateObject(String columnLabel, @Nullable Object x, SQLType targetSqlType,
      int scaleOrLength) throws SQLException {
    updateObject(findColumn(columnLabel), x, targetSqlType, scaleOrLength);
  }

  @Override
  public void updateObject(@Positive int columnIndex, @Nullable Object x, SQLType targetSqlType)
      throws SQLException {
    updateObject(columnIndex, x, targetSqlType, -1);
  }

  @Override
  public void updateObject(String columnLabel, @Nullable Object x, SQLType targetSqlType)
      throws SQLException {
    updateObject(findColumn(columnLabel), x, targetSqlType);
  }

  @Override
  public @Nullable RowId getRowId(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getRowId columnIndex: {0}", columnIndex);
    throw Driver.notImplemented(this.getClass(), "getRowId(int)");
  }

  @Override
  public @Nullable RowId getRowId(String columnName) throws SQLException {
    return getRowId(findColumn(columnName));
  }

  @Override
  public void updateRowId(@Positive int columnIndex, @Nullable RowId x) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "updateRowId(int, RowId)");
  }

  @Override
  public void updateRowId(String columnName, @Nullable RowId x) throws SQLException {
    updateRowId(findColumn(columnName), x);
  }

  @Override
  public int getHoldability() throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getHoldability()");
  }

  @Override
  public boolean isClosed() throws SQLException {
    return rows == null;
  }

  @Override
  public void updateNString(@Positive int columnIndex, @Nullable String nString) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "updateNString(int, String)");
  }

  @Override
  public void updateNString(String columnName, @Nullable String nString) throws SQLException {
    updateNString(findColumn(columnName), nString);
  }

  @Override
  public void updateNClob(@Positive int columnIndex, @Nullable NClob nClob) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "updateNClob(int, NClob)");
  }

  @Override
  public void updateNClob(String columnName, @Nullable NClob nClob) throws SQLException {
    updateNClob(findColumn(columnName), nClob);
  }

  @Override
  public void updateNClob(@Positive int columnIndex, @Nullable Reader reader) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "updateNClob(int, Reader)");
  }

  @Override
  public void updateNClob(String columnName, @Nullable Reader reader) throws SQLException {
    updateNClob(findColumn(columnName), reader);
  }

  @Override
  public void updateNClob(@Positive int columnIndex, @Nullable Reader reader, long length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "updateNClob(int, Reader, long)");
  }

  @Override
  public void updateNClob(String columnName, @Nullable Reader reader, long length) throws SQLException {
    updateNClob(findColumn(columnName), reader, length);
  }

  @Override
  public @Nullable NClob getNClob(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getNClob columnIndex: {0}", columnIndex);
    throw Driver.notImplemented(this.getClass(), "getNClob(int)");
  }

  @Override
  public @Nullable NClob getNClob(String columnName) throws SQLException {
    return getNClob(findColumn(columnName));
  }

  @Override
  public void updateBlob(@Positive int columnIndex, @Nullable InputStream inputStream, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(),
        "updateBlob(int, InputStream, long)");
  }

  @Override
  public void updateBlob(String columnName, @Nullable InputStream inputStream, long length)
      throws SQLException {
    updateBlob(findColumn(columnName), inputStream, length);
  }

  @Override
  public void updateBlob(@Positive int columnIndex, @Nullable InputStream inputStream) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "updateBlob(int, InputStream)");
  }

  @Override
  public void updateBlob(String columnName, @Nullable InputStream inputStream) throws SQLException {
    updateBlob(findColumn(columnName), inputStream);
  }

  @Override
  public void updateClob(@Positive int columnIndex, @Nullable Reader reader, long length) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "updateClob(int, Reader, long)");
  }

  @Override
  public void updateClob(String columnName, @Nullable Reader reader, long length) throws SQLException {
    updateClob(findColumn(columnName), reader, length);
  }

  @Override
  public void updateClob(@Positive int columnIndex, @Nullable Reader reader) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "updateClob(int, Reader)");
  }

  @Override
  public void updateClob(String columnName, @Nullable Reader reader) throws SQLException {
    updateClob(findColumn(columnName), reader);
  }

  @Override
  @Pure
  public @Nullable SQLXML getSQLXML(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getSQLXML columnIndex: {0}", columnIndex);
    String data = getString(columnIndex);
    if (data == null) {
      return null;
    }

    return new PgSQLXML(connection, data);
  }

  @Override
  public @Nullable SQLXML getSQLXML(String columnName) throws SQLException {
    return getSQLXML(findColumn(columnName));
  }

  @Override
  public void updateSQLXML(@Positive int columnIndex, @Nullable SQLXML xmlObject) throws SQLException {
    updateValue(columnIndex, xmlObject);
  }

  @Override
  public void updateSQLXML(String columnName, @Nullable SQLXML xmlObject) throws SQLException {
    updateSQLXML(findColumn(columnName), xmlObject);
  }

  @Override
  public @Nullable String getNString(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getNString columnIndex: {0}", columnIndex);
    throw Driver.notImplemented(this.getClass(), "getNString(int)");
  }

  @Override
  public @Nullable String getNString(String columnName) throws SQLException {
    return getNString(findColumn(columnName));
  }

  @Override
  public @Nullable Reader getNCharacterStream(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getNCharacterStream columnIndex: {0}", columnIndex);
    throw Driver.notImplemented(this.getClass(), "getNCharacterStream(int)");
  }

  @Override
  public @Nullable Reader getNCharacterStream(String columnName) throws SQLException {
    return getNCharacterStream(findColumn(columnName));
  }

  public void updateNCharacterStream(@Positive int columnIndex,
      @Nullable Reader x, int length) throws SQLException {
    throw Driver.notImplemented(this.getClass(),
        "updateNCharacterStream(int, Reader, int)");
  }

  public void updateNCharacterStream(String columnName,
      @Nullable Reader x, int length) throws SQLException {
    updateNCharacterStream(findColumn(columnName), x, length);
  }

  @Override
  public void updateNCharacterStream(@Positive int columnIndex,
      @Nullable Reader x) throws SQLException {
    throw Driver.notImplemented(this.getClass(),
        "updateNCharacterStream(int, Reader)");
  }

  @Override
  public void updateNCharacterStream(String columnName,
      @Nullable Reader x) throws SQLException {
    updateNCharacterStream(findColumn(columnName), x);
  }

  @Override
  public void updateNCharacterStream(@Positive int columnIndex,
      @Nullable Reader x, long length) throws SQLException {
    throw Driver.notImplemented(this.getClass(),
        "updateNCharacterStream(int, Reader, long)");
  }

  @Override
  public void updateNCharacterStream(String columnName,
      @Nullable Reader x, long length) throws SQLException {
    updateNCharacterStream(findColumn(columnName), x, length);
  }

  @Override
  public void updateCharacterStream(@Positive int columnIndex,
      @Nullable Reader reader, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(),
        "updateCharacterStream(int, Reader, long)");
  }

  @Override
  public void updateCharacterStream(String columnName,
      @Nullable Reader reader, long length)
      throws SQLException {
    updateCharacterStream(findColumn(columnName), reader, length);
  }

  @Override
  public void updateCharacterStream(@Positive int columnIndex,
      @Nullable Reader reader) throws SQLException {
    throw Driver.notImplemented(this.getClass(),
        "updateCharacterStream(int, Reader)");
  }

  @Override
  public void updateCharacterStream(String columnName,
      @Nullable Reader reader) throws SQLException {
    updateCharacterStream(findColumn(columnName), reader);
  }

  @Override
  public void updateBinaryStream(@Positive int columnIndex,
      @Nullable InputStream inputStream, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(),
        "updateBinaryStream(int, InputStream, long)");
  }

  @Override
  public void updateBinaryStream(String columnName,
      @Nullable InputStream inputStream, long length)
      throws SQLException {
    updateBinaryStream(findColumn(columnName), inputStream, length);
  }

  @Override
  public void updateBinaryStream(@Positive int columnIndex,
      @Nullable InputStream inputStream) throws SQLException {
    throw Driver.notImplemented(this.getClass(),
        "updateBinaryStream(int, InputStream)");
  }

  @Override
  public void updateBinaryStream(String columnName,
      @Nullable InputStream inputStream) throws SQLException {
    updateBinaryStream(findColumn(columnName), inputStream);
  }

  @Override
  public void updateAsciiStream(@Positive int columnIndex,
      @Nullable InputStream inputStream, long length)
      throws SQLException {
    throw Driver.notImplemented(this.getClass(),
        "updateAsciiStream(int, InputStream, long)");
  }

  @Override
  public void updateAsciiStream(String columnName,
      @Nullable InputStream inputStream, long length)
      throws SQLException {
    updateAsciiStream(findColumn(columnName), inputStream, length);
  }

  @Override
  public void updateAsciiStream(@Positive int columnIndex,
      @Nullable InputStream inputStream) throws SQLException {
    throw Driver.notImplemented(this.getClass(),
        "updateAsciiStream(int, InputStream)");
  }

  @Override
  public void updateAsciiStream(String columnName,
      @Nullable InputStream inputStream) throws SQLException {
    updateAsciiStream(findColumn(columnName), inputStream);
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isAssignableFrom(getClass());
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isAssignableFrom(getClass())) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }

  private Calendar getDefaultCalendar() {
    return dateTimeHelper.getDefaultCalendar();
  }

  private TimestampUtils getTimestampUtils() {
    return dateTimeHelper.getTimestampUtils();
  }

  /**
   * This is here to be used by metadata functions
   * to make all column labels upper case.
   * Because postgres folds columns to lower case in queries it will be easier
   * to change the fields after the fact rather than try to coerce all the columns
   * to upper case in the queries as this would require surrounding all columns with " and
   * escaping them making them even harder to read than they are now.
   * @return PgResultSet
   */
  protected PgResultSet upperCaseFieldLabels() {
    for (Field field: fields ) {
      field.upperCaseLabel();
    }
    return this;
  }
}
