/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.PGResultSetMetaData;
import org.postgresql.PGStatement;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.Encoding;
import org.postgresql.core.Field;
import org.postgresql.core.Oid;
import org.postgresql.core.Query;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.ResultHandlerBase;
import org.postgresql.core.Tuple;
import org.postgresql.core.TypeInfo;
import org.postgresql.core.Utils;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.HStoreConverter;
import org.postgresql.util.JdbcBlackHole;
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
import java.io.CharArrayReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class PgResultSet implements ResultSet, org.postgresql.PGRefCursorResultSet {

  // needed for updateable result set support
  private boolean updateable = false;
  private boolean doingUpdates = false;
  private @Nullable HashMap<String, Object> updateValues = null;
  private boolean usingOID = false; // are we using the OID for the primary key?
  private @Nullable List<PrimaryKey> primaryKeys; // list of primary keys
  private boolean singleTable = false;
  private String onlyTable = "";
  private @Nullable String tableName = null;
  private @Nullable PreparedStatement deleteStatement = null;
  private final int resultsettype;
  private final int resultsetconcurrency;
  private int fetchdirection = ResultSet.FETCH_UNKNOWN;
  private @Nullable TimeZone defaultTimeZone;
  protected final BaseConnection connection; // the connection we belong to
  protected final BaseStatement statement; // the statement we belong to
  protected final Field[] fields; // Field metadata for this resultset.
  protected final @Nullable Query originalQuery; // Query we originated from

  protected final int maxRows; // Maximum rows in this resultset (might be 0).
  protected final int maxFieldSize; // Maximum field size in this resultset (might be 0).

  protected @Nullable List<Tuple> rows; // Current page of results.
  protected int currentRow = -1; // Index into 'rows' of our currrent row (0-based)
  protected int rowOffset; // Offset of row 0 in the actual resultset
  protected @Nullable Tuple thisRow; // copy of the current result row
  protected @Nullable SQLWarning warnings = null; // The warning chain
  /**
   * True if the last obtained column value was SQL NULL as specified by {@link #wasNull}. The value
   * is always updated by the {@link #getRawValue} method.
   */
  protected boolean wasNullFlag = false;
  protected boolean onInsertRow = false;
  // are we on the insert row (for JDBC2 updatable resultsets)?

  private @Nullable Tuple rowBuffer = null; // updateable rowbuffer

  protected int fetchSize; // Current fetch size (might be 0).
  protected @Nullable ResultCursor cursor; // Cursor for fetching additional data.

  // Speed up findColumn by caching lookups
  private @Nullable Map<String, Integer> columnNameIndexMap;

  private @Nullable ResultSetMetaData rsMetaData;

  protected ResultSetMetaData createMetaData() throws SQLException {
    return new PgResultSetMetaData(connection, fields);
  }

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
      int rsHoldability) throws SQLException {
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
    this.fields = fields;
    this.rows = tuples;
    this.cursor = cursor;
    this.maxRows = maxRows;
    this.maxFieldSize = maxFieldSize;
    this.resultsettype = rsType;
    this.resultsetconcurrency = rsConcurrency;
  }

  public java.net.URL getURL(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getURL columnIndex: {0}", columnIndex);
    checkClosed();
    throw org.postgresql.Driver.notImplemented(this.getClass(), "getURL(int)");
  }

  public java.net.URL getURL(String columnName) throws SQLException {
    return getURL(findColumn(columnName));
  }

  @RequiresNonNull({"thisRow"})
  protected @Nullable Object internalGetObject(@Positive int columnIndex, Field field) throws SQLException {
    castNonNull(thisRow, "thisRow");
    switch (getSQLType(columnIndex)) {
      case Types.BOOLEAN:
      case Types.BIT:
        return getBoolean(columnIndex);
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
            (field.getMod() == -1) ? -1 : ((field.getMod() - 4) & 0xffff), true);
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
        return getDate(columnIndex);
      case Types.TIME:
        return getTime(columnIndex);
      case Types.TIMESTAMP:
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
        if (type.equals("unknown")) {
          return getString(columnIndex);
        }

        if (type.equals("uuid")) {
          if (isBinary(columnIndex)) {
            return getUUID(castNonNull(thisRow.get(columnIndex - 1)));
          }
          return getUUID(castNonNull(getString(columnIndex)));
        }

        // Specialized support for ref cursors is neater.
        if (type.equals("refcursor")) {
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
          //
          // In long running transactions these backend cursors take up memory space
          // we could close in rs.close(), but if the transaction is closed before the result set,
          // then
          // the cursor no longer exists

          sb.setLength(0);
          sb.append("CLOSE ");
          Utils.escapeIdentifier(sb, cursorName);
          connection.execSQLUpdate(sb.toString());
          ((PgResultSet) rs).setRefCursor(cursorName);
          return rs;
        }
        if ("hstore".equals(type)) {
          if (isBinary(columnIndex)) {
            return HStoreConverter.fromBytes(castNonNull(thisRow.get(columnIndex - 1)),
                connection.getEncoding());
          }
          return HStoreConverter.fromString(castNonNull(getString(columnIndex)));
        }

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

    final int rows_size = rows.size();

    // if index<0, count from the end of the result set, but check
    // to be sure that it is not beyond the first index
    if (index < 0) {
      if (index >= -rows_size) {
        internalIndex = rows_size + index;
      } else {
        beforeFirst();
        return false;
      }
    } else {
      // must be the case that index>0,
      // find the correct place, assuming that
      // the index is not too large
      if (index <= rows_size) {
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

    final int rows_size = rows.size();
    if (rows_size > 0) {
      currentRow = rows_size;
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

  public java.math.@Nullable BigDecimal getBigDecimal(@Positive int columnIndex) throws SQLException {
    return getBigDecimal(columnIndex, -1);
  }

  public java.math.@Nullable BigDecimal getBigDecimal(String columnName) throws SQLException {
    return getBigDecimal(findColumn(columnName));
  }

  public @Nullable Blob getBlob(String columnName) throws SQLException {
    return getBlob(findColumn(columnName));
  }

  protected Blob makeBlob(long oid) throws SQLException {
    return new PgBlob(connection, oid);
  }

  @Pure
  public @Nullable Blob getBlob(int i) throws SQLException {
    byte[] value = getRawValue(i);
    if (value == null) {
      return null;
    }

    return makeBlob(getLong(i));
  }

  public java.io.@Nullable Reader getCharacterStream(String columnName) throws SQLException {
    return getCharacterStream(findColumn(columnName));
  }

  public java.io.@Nullable Reader getCharacterStream(int i) throws SQLException {
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
    return new CharArrayReader(value.toCharArray());
  }

  public @Nullable Clob getClob(String columnName) throws SQLException {
    return getClob(findColumn(columnName));
  }

  protected Clob makeClob(long oid) throws SQLException {
    return new PgClob(connection, oid);
  }

  @Pure
  public @Nullable Clob getClob(int i) throws SQLException {
    byte[] value = getRawValue(i);
    if (value == null) {
      return null;
    }

    return makeClob(getLong(i));
  }

  public int getConcurrency() throws SQLException {
    checkClosed();
    return resultsetconcurrency;
  }

  @Override
  public java.sql.@Nullable Date getDate(
      int i, java.util.@Nullable Calendar cal) throws SQLException {
    byte[] value = getRawValue(i);
    if (value == null) {
      return null;
    }

    if (cal == null) {
      cal = getDefaultCalendar();
    }
    if (isBinary(i)) {
      int col = i - 1;
      int oid = fields[col].getOID();
      TimeZone tz = cal.getTimeZone();
      if (oid == Oid.DATE) {
        return connection.getTimestampUtils().toDateBin(tz, value);
      } else if (oid == Oid.TIMESTAMP || oid == Oid.TIMESTAMPTZ) {
        // If backend provides just TIMESTAMP, we use "cal" timezone
        // If backend provides TIMESTAMPTZ, we ignore "cal" as we know true instant value
        Timestamp timestamp = castNonNull(getTimestamp(i, cal));
        // Here we just truncate date to 00:00 in a given time zone
        return connection.getTimestampUtils().convertToDate(timestamp.getTime(), tz);
      } else {
        throw new PSQLException(
            GT.tr("Cannot convert the column of type {0} to requested type {1}.",
                Oid.toString(oid), "date"),
            PSQLState.DATA_TYPE_MISMATCH);
      }
    }

    return connection.getTimestampUtils().toDate(cal, castNonNull(getString(i)));
  }

  @Override
  public @Nullable Time getTime(
      int i, java.util.@Nullable Calendar cal) throws SQLException {
    byte[] value = getRawValue(i);
    if (value == null) {
      return null;
    }

    if (cal == null) {
      cal = getDefaultCalendar();
    }
    if (isBinary(i)) {
      int col = i - 1;
      int oid = fields[col].getOID();
      TimeZone tz = cal.getTimeZone();
      if (oid == Oid.TIME || oid == Oid.TIMETZ) {
        return connection.getTimestampUtils().toTimeBin(tz, value);
      } else if (oid == Oid.TIMESTAMP || oid == Oid.TIMESTAMPTZ) {
        // If backend provides just TIMESTAMP, we use "cal" timezone
        // If backend provides TIMESTAMPTZ, we ignore "cal" as we know true instant value
        Timestamp timestamp = getTimestamp(i, cal);
        if (timestamp == null) {
          return null;
        }
        long timeMillis = timestamp.getTime();
        if (oid == Oid.TIMESTAMPTZ) {
          // time zone == UTC since BINARY "timestamp with time zone" is always sent in UTC
          // So we truncate days
          return new Time(timeMillis % TimeUnit.DAYS.toMillis(1));
        }
        // Here we just truncate date part
        return connection.getTimestampUtils().convertToTime(timeMillis, tz);
      } else {
        throw new PSQLException(
            GT.tr("Cannot convert the column of type {0} to requested type {1}.",
                Oid.toString(oid), "time"),
            PSQLState.DATA_TYPE_MISMATCH);
      }
    }

    String string = getString(i);
    return connection.getTimestampUtils().toTime(cal, string);
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  private java.time.@Nullable LocalTime getLocalTime(int i) throws SQLException {
    byte[] value = getRawValue(i);
    if (value == null) {
      return null;
    }

    if (isBinary(i)) {
      int col = i - 1;
      int oid = fields[col].getOID();
      if (oid == Oid.TIME) {
        return connection.getTimestampUtils().toLocalTimeBin(value);
      } else {
        throw new PSQLException(
            GT.tr("Cannot convert the column of type {0} to requested type {1}.",
                Oid.toString(oid), "time"),
            PSQLState.DATA_TYPE_MISMATCH);
      }
    }

    String string = getString(i);
    return connection.getTimestampUtils().toLocalTime(string);
  }
  //#endif

  @Pure
  @Override
  public @Nullable Timestamp getTimestamp(
      int i, java.util.@Nullable Calendar cal) throws SQLException {
    byte[] value = getRawValue(i);
    if (value == null) {
      return null;
    }

    if (cal == null) {
      cal = getDefaultCalendar();
    }
    int col = i - 1;
    int oid = fields[col].getOID();
    if (isBinary(i)) {
      if (oid == Oid.TIMESTAMPTZ || oid == Oid.TIMESTAMP) {
        boolean hasTimeZone = oid == Oid.TIMESTAMPTZ;
        TimeZone tz = cal.getTimeZone();
        return connection.getTimestampUtils().toTimestampBin(tz, value, hasTimeZone);
      } else {
        // JDBC spec says getTimestamp of Time and Date must be supported
        long millis;
        if (oid == Oid.TIME || oid == Oid.TIMETZ) {
          Time time = getTime(i, cal);
          if (time == null) {
            return null;
          }
          millis = time.getTime();
        } else if (oid == Oid.DATE) {
          Date date = getDate(i, cal);
          if (date == null) {
            return null;
          }
          millis = date.getTime();
        } else {
          throw new PSQLException(
              GT.tr("Cannot convert the column of type {0} to requested type {1}.",
                  Oid.toString(oid), "timestamp"),
              PSQLState.DATA_TYPE_MISMATCH);
        }
        return new Timestamp(millis);
      }
    }

    // If this is actually a timestamptz, the server-provided timezone will override
    // the one we pass in, which is the desired behaviour. Otherwise, we'll
    // interpret the timezone-less value in the provided timezone.
    String string = castNonNull(getString(i));
    if (oid == Oid.TIME || oid == Oid.TIMETZ) {
      // If server sends us a TIME, we ensure java counterpart has date of 1970-01-01
      return new Timestamp(connection.getTimestampUtils().toTime(cal, string).getTime());
    }
    return connection.getTimestampUtils().toTimestamp(cal, string);
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  private java.time.@Nullable OffsetDateTime getOffsetDateTime(int i) throws SQLException {
    byte[] value = getRawValue(i);
    if (value == null) {
      return null;
    }

    int col = i - 1;
    int oid = fields[col].getOID();

    if (isBinary(i)) {
      if (oid == Oid.TIMESTAMPTZ || oid == Oid.TIMESTAMP) {
        return connection.getTimestampUtils().toOffsetDateTimeBin(value);
      } else if (oid == Oid.TIMETZ) {
        // JDBC spec says timetz must be supported
        Time time = getTime(i);
        if (time == null) {
          return null;
        }
        return connection.getTimestampUtils().toOffsetDateTime(time);
      } else {
        throw new PSQLException(
            GT.tr("Cannot convert the column of type {0} to requested type {1}.",
                Oid.toString(oid), "timestamptz"),
            PSQLState.DATA_TYPE_MISMATCH);
      }
    }

    // If this is actually a timestamptz, the server-provided timezone will override
    // the one we pass in, which is the desired behaviour. Otherwise, we'll
    // interpret the timezone-less value in the provided timezone.
    String string = castNonNull(getString(i));
    if (oid == Oid.TIMETZ) {
      // JDBC spec says timetz must be supported
      // If server sends us a TIMETZ, we ensure java counterpart has date of 1970-01-01
      Calendar cal = getDefaultCalendar();
      Time time = connection.getTimestampUtils().toTime(cal, string);
      return connection.getTimestampUtils().toOffsetDateTime(time);
    }
    return connection.getTimestampUtils().toOffsetDateTime(string);
  }

  private java.time.@Nullable LocalDateTime getLocalDateTime(int i) throws SQLException {
    byte[] value = getRawValue(i);
    if (value == null) {
      return null;
    }

    int col = i - 1;
    int oid = fields[col].getOID();
    if (oid != Oid.TIMESTAMP) {
      throw new PSQLException(
              GT.tr("Cannot convert the column of type {0} to requested type {1}.",
                  Oid.toString(oid), "timestamp"),
              PSQLState.DATA_TYPE_MISMATCH);
    }
    if (isBinary(i)) {
      return connection.getTimestampUtils().toLocalDateTimeBin(value);
    }

    String string = castNonNull(getString(i));
    return connection.getTimestampUtils().toLocalDateTime(string);
  }
  //#endif

  public java.sql.@Nullable Date getDate(
      String c, java.util.@Nullable Calendar cal) throws SQLException {
    return getDate(findColumn(c), cal);
  }

  public @Nullable Time getTime(
      String c, java.util.@Nullable Calendar cal) throws SQLException {
    return getTime(findColumn(c), cal);
  }

  public @Nullable Timestamp getTimestamp(
      String c, java.util.@Nullable Calendar cal) throws SQLException {
    return getTimestamp(findColumn(c), cal);
  }

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
    throw org.postgresql.Driver.notImplemented(this.getClass(), "getObjectImpl(int,Map)");
  }

  public @Nullable Ref getRef(String columnName) throws SQLException {
    return getRef(findColumn(columnName));
  }

  public @Nullable Ref getRef(int i) throws SQLException {
    checkClosed();
    // The backend doesn't yet have SQL3 REF types
    throw org.postgresql.Driver.notImplemented(this.getClass(), "getRef(int)");
  }

  @Override
  public int getRow() throws SQLException {
    checkClosed();

    if (onInsertRow) {
      return 0;
    }

    final int rows_size = rows.size();

    if (currentRow < 0 || currentRow >= rows_size) {
      return 0;
    }

    return rowOffset + currentRow + 1;
  }

  // This one needs some thought, as not all ResultSets come from a statement
  public Statement getStatement() throws SQLException {
    checkClosed();
    return statement;
  }

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
    final int rows_size = rows.size();
    if (rowOffset + rows_size == 0) {
      return false;
    }
    return (currentRow >= rows_size);
  }

  @Pure
  @Override
  public boolean isBeforeFirst() throws SQLException {
    checkClosed();
    if (onInsertRow) {
      return false;
    }

    return ((rowOffset + currentRow) < 0 && !castNonNull(rows, "rows").isEmpty());
  }

  @Override
  public boolean isFirst() throws SQLException {
    checkClosed();
    if (onInsertRow) {
      return false;
    }

    final int rows_size = rows.size();
    if (rowOffset + rows_size == 0) {
      return false;
    }

    return ((rowOffset + currentRow) == 0);
  }

  @Override
  public boolean isLast() throws SQLException {
    checkClosed();
    if (onInsertRow) {
      return false;
    }

    List<Tuple> rows = castNonNull(this.rows, "rows");
    final int rows_size = rows.size();

    if (rows_size == 0) {
      return false; // No rows.
    }

    if (currentRow != (rows_size - 1)) {
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

    rowOffset += rows_size - 1; // Discarding all but one row.

    // Work out how many rows maxRows will let us fetch.
    int fetchRows = fetchSize;
    if (maxRows != 0) {
      if (fetchRows == 0 || rowOffset + fetchRows > maxRows) {
        // Fetch would exceed maxRows, limit it.
        fetchRows = maxRows - rowOffset;
      }
    }

    // Do the actual fetch.
    connection.getQueryExecutor().fetch(cursor, new CursorResultHandler(), fetchRows);

    rows = castNonNull(this.rows, "rows");
    // Now prepend our one saved row and move to it.
    rows.add(0, castNonNull(thisRow));
    currentRow = 0;

    // Finally, now we can tell if we're the last row or not.
    return (rows.size() == 1);
  }

  @Override
  public boolean last() throws SQLException {
    checkScrollable();
    List<Tuple> rows = castNonNull(this.rows, "rows");
    final int rows_size = rows.size();
    if (rows_size <= 0) {
      return false;
    }

    currentRow = rows_size - 1;
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

  public synchronized void cancelRowUpdates() throws SQLException {
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

  public synchronized void deleteRow() throws SQLException {
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

      deleteStatement = connection.prepareStatement(deleteSQL.toString());
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

  @Override
  public synchronized void insertRow() throws SQLException {
    checkUpdateable();
    castNonNull(rows, "rows");
    if (!onInsertRow) {
      throw new PSQLException(GT.tr("Not on the insert row."), PSQLState.INVALID_CURSOR_STATE);
    }
    HashMap<String, Object> updateValues = this.updateValues;
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

      Iterator<Object> values = updateValues.values().iterator();

      for (int i = 1; values.hasNext(); i++) {
        insertStatement.setObject(i, values.next());
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

  @Override
  public synchronized void moveToCurrentRow() throws SQLException {
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

  @Override
  public synchronized void moveToInsertRow() throws SQLException {
    checkUpdateable();

    // make sure the underlying data is null
    clearRowBuffer(false);

    onInsertRow = true;
    doingUpdates = false;
  }

  // rowBuffer is the temporary storage for the row
  private synchronized void clearRowBuffer(boolean copyCurrentRow) throws SQLException {
    // inserts want an empty array while updates want a copy of the current row
    if (copyCurrentRow) {
      rowBuffer = castNonNull(thisRow, "thisRow").updateableCopy();
    } else {
      rowBuffer = new Tuple(fields.length);
    }

    // clear the updateValues hash map for the next set of updates
    HashMap<String, Object> updateValues = this.updateValues;
    if (updateValues != null) {
      updateValues.clear();
    }
  }

  public boolean rowDeleted() throws SQLException {
    checkClosed();
    return false;
  }

  public boolean rowInserted() throws SQLException {
    checkClosed();
    return false;
  }

  public boolean rowUpdated() throws SQLException {
    checkClosed();
    return false;
  }

  public synchronized void updateAsciiStream(@Positive int columnIndex,
      java.io.@Nullable InputStream x, int length)
      throws SQLException {
    if (x == null) {
      updateNull(columnIndex);
      return;
    }

    try {
      InputStreamReader reader = new InputStreamReader(x, "ASCII");
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
    } catch (UnsupportedEncodingException uee) {
      throw new PSQLException(GT.tr("The JVM claims not to support the encoding: {0}", "ASCII"),
          PSQLState.UNEXPECTED_ERROR, uee);
    } catch (IOException ie) {
      throw new PSQLException(GT.tr("Provided InputStream failed."), null, ie);
    }
  }

  public synchronized void updateBigDecimal(@Positive int columnIndex, java.math.@Nullable BigDecimal x)
      throws SQLException {
    updateValue(columnIndex, x);
  }

  public synchronized void updateBinaryStream(@Positive int columnIndex,
      java.io.@Nullable InputStream x, int length)
      throws SQLException {
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

  public synchronized void updateBoolean(@Positive int columnIndex, boolean x) throws SQLException {
    updateValue(columnIndex, x);
  }

  public synchronized void updateByte(@Positive int columnIndex, byte x) throws SQLException {
    updateValue(columnIndex, String.valueOf(x));
  }

  public synchronized void updateBytes(@Positive int columnIndex, byte @Nullable [] x) throws SQLException {
    updateValue(columnIndex, x);
  }

  public synchronized void updateCharacterStream(@Positive int columnIndex,
      java.io.@Nullable Reader x, int length)
      throws SQLException {
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

  public synchronized void updateDate(@Positive int columnIndex,
      java.sql.@Nullable Date x) throws SQLException {
    updateValue(columnIndex, x);
  }

  public synchronized void updateDouble(@Positive int columnIndex, double x) throws SQLException {
    updateValue(columnIndex, x);
  }

  public synchronized void updateFloat(@Positive int columnIndex, float x) throws SQLException {
    updateValue(columnIndex, x);
  }

  public synchronized void updateInt(@Positive int columnIndex, int x) throws SQLException {
    updateValue(columnIndex, x);
  }

  public synchronized void updateLong(@Positive int columnIndex, long x) throws SQLException {
    updateValue(columnIndex, x);
  }

  public synchronized void updateNull(@Positive int columnIndex) throws SQLException {
    checkColumnIndex(columnIndex);
    String columnTypeName = getPGType(columnIndex);
    updateValue(columnIndex, new NullObject(columnTypeName));
  }

  public synchronized void updateObject(
      int columnIndex, @Nullable Object x) throws SQLException {
    updateValue(columnIndex, x);
  }

  public synchronized void updateObject(
      int columnIndex, @Nullable Object x, int scale) throws SQLException {
    this.updateObject(columnIndex, x);
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
      selectSQL.append(pgmd.getBaseColumnName(i));
    }
    selectSQL.append(" from ").append(onlyTable).append(tableName).append(" where ");

    List<PrimaryKey> primaryKeys = castNonNull(this.primaryKeys, "primaryKeys");
    int numKeys = primaryKeys.size();

    for (int i = 0; i < numKeys; i++) {

      PrimaryKey primaryKey = primaryKeys.get(i);
      selectSQL.append(primaryKey.name).append("= ?");

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

      for (int j = 0, i = 1; j < numKeys; j++, i++) {
        selectStatement.setObject(i, primaryKeys.get(j).getValue());
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
  public synchronized void updateRow() throws SQLException {
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

    HashMap<String, Object> updateValues = castNonNull(this.updateValues);
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
      Iterator<Object> iterator = updateValues.values().iterator();
      for (; iterator.hasNext(); i++) {
        Object o = iterator.next();
        updateStatement.setObject(i + 1, o);
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
    doingUpdates = false;
  }

  public synchronized void updateShort(@Positive int columnIndex, short x) throws SQLException {
    updateValue(columnIndex, x);
  }

  public synchronized void updateString(@Positive int columnIndex, @Nullable String x) throws SQLException {
    updateValue(columnIndex, x);
  }

  public synchronized void updateTime(@Positive int columnIndex, @Nullable Time x) throws SQLException {
    updateValue(columnIndex, x);
  }

  public synchronized void updateTimestamp(
      int columnIndex, @Nullable Timestamp x) throws SQLException {
    updateValue(columnIndex, x);

  }

  public synchronized void updateNull(String columnName) throws SQLException {
    updateNull(findColumn(columnName));
  }

  public synchronized void updateBoolean(String columnName, boolean x) throws SQLException {
    updateBoolean(findColumn(columnName), x);
  }

  public synchronized void updateByte(String columnName, byte x) throws SQLException {
    updateByte(findColumn(columnName), x);
  }

  public synchronized void updateShort(String columnName, short x) throws SQLException {
    updateShort(findColumn(columnName), x);
  }

  public synchronized void updateInt(String columnName, int x) throws SQLException {
    updateInt(findColumn(columnName), x);
  }

  public synchronized void updateLong(String columnName, long x) throws SQLException {
    updateLong(findColumn(columnName), x);
  }

  public synchronized void updateFloat(String columnName, float x) throws SQLException {
    updateFloat(findColumn(columnName), x);
  }

  public synchronized void updateDouble(String columnName, double x) throws SQLException {
    updateDouble(findColumn(columnName), x);
  }

  public synchronized void updateBigDecimal(
      String columnName, @Nullable BigDecimal x) throws SQLException {
    updateBigDecimal(findColumn(columnName), x);
  }

  public synchronized void updateString(
      String columnName, @Nullable String x) throws SQLException {
    updateString(findColumn(columnName), x);
  }

  public synchronized void updateBytes(
      String columnName, byte @Nullable [] x) throws SQLException {
    updateBytes(findColumn(columnName), x);
  }

  public synchronized void updateDate(
      String columnName, java.sql.@Nullable Date x) throws SQLException {
    updateDate(findColumn(columnName), x);
  }

  public synchronized void updateTime(
      String columnName, java.sql.@Nullable Time x) throws SQLException {
    updateTime(findColumn(columnName), x);
  }

  public synchronized void updateTimestamp(
      String columnName, java.sql.@Nullable Timestamp x)
      throws SQLException {
    updateTimestamp(findColumn(columnName), x);
  }

  public synchronized void updateAsciiStream(
      String columnName, java.io.@Nullable InputStream x, int length)
      throws SQLException {
    updateAsciiStream(findColumn(columnName), x, length);
  }

  public synchronized void updateBinaryStream(
      String columnName, java.io.@Nullable InputStream x, int length)
      throws SQLException {
    updateBinaryStream(findColumn(columnName), x, length);
  }

  public synchronized void updateCharacterStream(
      String columnName, java.io.@Nullable Reader reader,
      int length) throws SQLException {
    updateCharacterStream(findColumn(columnName), reader, length);
  }

  public synchronized void updateObject(
      String columnName, @Nullable Object x, int scale)
      throws SQLException {
    updateObject(findColumn(columnName), x);
  }

  public synchronized void updateObject(
      String columnName, @Nullable Object x) throws SQLException {
    updateObject(findColumn(columnName), x);
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

    List<PrimaryKey> primaryKeys = new ArrayList<PrimaryKey>();
    this.primaryKeys = primaryKeys;

    int i = 0;
    int numPKcolumns = 0;

    // otherwise go and get the primary keys and create a list of keys
    @Nullable String[] s = quotelessTableName(castNonNull(tableName));
    String quotelessTableName = castNonNull(s[0]);
    @Nullable String quotelessSchemaName = s[1];
    java.sql.ResultSet rs = ((PgDatabaseMetaData)connection.getMetaData()).getPrimaryUniqueKeys("",
        quotelessSchemaName, quotelessTableName);

    while (rs.next()) {
      numPKcolumns++;
      String columnName = castNonNull(rs.getString(4)); // get the columnName
      int index = findColumnIndex(columnName);

      /* make sure that the user has included the primary key in the resultset */
      if (index > 0) {
        i++;
        primaryKeys.add(new PrimaryKey(index, columnName)); // get the primary key information
      }
    }

    rs.close();
    connection.getLogger().log(Level.FINE, "no of keys={0}", i);

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
      throw new PSQLException(GT.tr("No primary key found for table {0}.", tableName),
          PSQLState.INVALID_CURSOR_STATE);
    }

    return updateable;
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
          acc.append((betweenQuotes) ? c : Character.toLowerCase(c));
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
    if (valueObject instanceof PGobject) {
      String value = ((PGobject) valueObject).getValue();
      rowBuffer.set(columnIndex, (value == null) ? null : connection.encodeString(value));
    } else {
      if (valueObject == null) {
        rowBuffer.set(columnIndex, null);
        return;
      }
      switch (getSQLType(columnIndex + 1)) {

        // boolean needs to be formatted as t or f instead of true or false
        case Types.BIT:
        case Types.BOOLEAN:
          rowBuffer.set(columnIndex, connection
              .encodeString((Boolean) valueObject ? "t" : "f"));
          break;
        //
        // toString() isn't enough for date and time types; we must format it correctly
        // or we won't be able to re-parse it.
        //
        case Types.DATE:
          rowBuffer.set(columnIndex, connection
              .encodeString(
                  connection.getTimestampUtils().toString(
                      getDefaultCalendar(), (Date) valueObject)));
          break;

        case Types.TIME:
          rowBuffer.set(columnIndex, connection
              .encodeString(
                  connection.getTimestampUtils().toString(
                      getDefaultCalendar(), (Time) valueObject)));
          break;

        case Types.TIMESTAMP:
          rowBuffer.set(columnIndex, connection.encodeString(
              connection.getTimestampUtils().toString(
                  getDefaultCalendar(), (Timestamp) valueObject)));
          break;

        case Types.NULL:
          // Should never happen?
          break;

        case Types.BINARY:
        case Types.LONGVARBINARY:
        case Types.VARBINARY:
          if (isBinary(columnIndex + 1)) {
            rowBuffer.set(columnIndex, (byte[]) valueObject);
          } else {
            try {
              rowBuffer.set(columnIndex,
                  PGbytea.toPGString((byte[]) valueObject).getBytes(connection.getEncoding().name()));
            } catch (UnsupportedEncodingException e) {
              throw new PSQLException(
                  GT.tr("The JVM claims not to support the encoding: {0}", connection.getEncoding().name()),
                  PSQLState.UNEXPECTED_ERROR, e);
            }
          }
          break;

        default:
          rowBuffer.set(columnIndex, connection.encodeString(String.valueOf(valueObject)));
          break;
      }

    }
  }

  private void updateRowBuffer(@Nullable PreparedStatement insertStatement,
      Tuple rowBuffer, HashMap<String, Object> updateValues) throws SQLException {
    for (Map.Entry<String, Object> entry : updateValues.entrySet()) {
      int columnIndex = findColumn(entry.getKey()) - 1;
      Object valueObject = entry.getValue();
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
    public void handleCompletion() throws SQLException {
      SQLWarning warning = getWarning();
      if (warning != null) {
        PgResultSet.this.addWarning(warning);
      }
      super.handleCompletion();
    }
  }

  public BaseStatement getPGStatement() {
    return statement;
  }

  //
  // Backwards compatibility with PGRefCursorResultSet
  //

  private @Nullable String refCursorName;

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

  public void setFetchSize(int rows) throws SQLException {
    checkClosed();
    if (rows < 0) {
      throw new PSQLException(GT.tr("Fetch size must be a value greater to or equal to 0."),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    fetchSize = rows;
  }

  public int getFetchSize() throws SQLException {
    checkClosed();
    return fetchSize;
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
      if (maxRows != 0) {
        if (fetchRows == 0 || rowOffset + fetchRows > maxRows) {
          // Fetch would exceed maxRows, limit it.
          fetchRows = maxRows - rowOffset;
        }
      }

      // Execute the fetch and update this resultset.
      connection.getQueryExecutor().fetch(cursor, new CursorResultHandler(), fetchRows);

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
    JdbcBlackHole.close(deleteStatement);
    deleteStatement = null;
    if (cursor != null) {
      cursor.close();
      cursor = null;
    }
  }

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
      Field field = fields[columnIndex - 1];
      Object obj = internalGetObject(columnIndex, field);
      if (obj == null) {
        // internalGetObject() knows jdbc-types and some extra like hstore. It does not know of
        // PGobject based types like geometric types but getObject does
        obj = getObject(columnIndex);
        if (obj == null) {
          return null;
        }
        return obj.toString();
      }
      // hack to be compatible with text protocol
      if (obj instanceof java.util.Date) {
        int oid = field.getOID();
        return connection.getTimestampUtils().timeToString((java.util.Date) obj,
            oid == Oid.TIMESTAMPTZ || oid == Oid.TIMETZ);
      }
      if ("hstore".equals(getPGType(columnIndex))) {
        return HStoreConverter.toString((Map<?, ?>) obj);
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
   * <p>Retrieves the value of the designated column in the current row of this <code>ResultSet</code>
   * object as a <code>boolean</code> in the Java programming language.</p>
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
   * @exception SQLException if the columnIndex is not valid; if a database access error occurs; if
   *            this method is called on a closed result set or is an invalid cast to boolean type.
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

    int col = columnIndex - 1;
    if (Oid.BOOL == fields[col].getOID()) {
      final byte[] v = value;
      return (1 == v.length) && (116 == v[0]); // 116 = 't'
    }

    if (isBinary(columnIndex)) {
      return BooleanTypeUtil.castToBoolean(readDoubleValue(value, fields[col].getOID(), "boolean"));
    }

    String stringValue = castNonNull(getString(columnIndex));
    return BooleanTypeUtil.castToBoolean(stringValue);
  }

  private static final BigInteger BYTEMAX = new BigInteger(Byte.toString(Byte.MAX_VALUE));
  private static final BigInteger BYTEMIN = new BigInteger(Byte.toString(Byte.MIN_VALUE));

  @Override
  public byte getByte(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getByte columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return 0; // SQL NULL
    }

    if (isBinary(columnIndex)) {
      int col = columnIndex - 1;
      // there is no Oid for byte so must always do conversion from
      // some other numeric type
      return (byte) readLongValue(value, fields[col].getOID(), Byte.MIN_VALUE,
          Byte.MAX_VALUE, "byte");
    }

    String s = getString(columnIndex);

    if (s != null) {
      s = s.trim();
      if (s.isEmpty()) {
        return 0;
      }
      try {
        // try the optimal parse
        return Byte.parseByte(s);
      } catch (NumberFormatException e) {
        // didn't work, assume the column is not a byte
        try {
          BigDecimal n = new BigDecimal(s);
          BigInteger i = n.toBigInteger();

          int gt = i.compareTo(BYTEMAX);
          int lt = i.compareTo(BYTEMIN);

          if (gt > 0 || lt < 0) {
            throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "byte", s),
                PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
          }
          return i.byteValue();
        } catch (NumberFormatException ex) {
          throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "byte", s),
              PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
        }
      }
    }
    return 0; // SQL NULL
  }

  @Override
  public short getShort(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getShort columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return 0; // SQL NULL
    }

    if (isBinary(columnIndex)) {
      int col = columnIndex - 1;
      int oid = fields[col].getOID();
      if (oid == Oid.INT2) {
        return ByteConverter.int2(value, 0);
      }
      return (short) readLongValue(value, oid, Short.MIN_VALUE, Short.MAX_VALUE, "short");
    }

    return toShort(getFixedString(columnIndex));
  }

  @Pure
  @Override
  public int getInt(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getInt columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return 0; // SQL NULL
    }

    if (isBinary(columnIndex)) {
      int col = columnIndex - 1;
      int oid = fields[col].getOID();
      if (oid == Oid.INT4) {
        return ByteConverter.int4(value, 0);
      }
      return (int) readLongValue(value, oid, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
    }

    Encoding encoding = connection.getEncoding();
    if (encoding.hasAsciiNumbers()) {
      try {
        return getFastInt(value);
      } catch (NumberFormatException ignored) {
      }
    }
    return toInt(getFixedString(columnIndex));
  }

  @Pure
  @Override
  public long getLong(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getLong columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return 0; // SQL NULL
    }

    if (isBinary(columnIndex)) {
      int col = columnIndex - 1;
      int oid = fields[col].getOID();
      if (oid == Oid.INT8) {
        return ByteConverter.int8(value, 0);
      }
      return readLongValue(value, oid, Long.MIN_VALUE, Long.MAX_VALUE, "long");
    }

    Encoding encoding = connection.getEncoding();
    if (encoding.hasAsciiNumbers()) {
      try {
        return getFastLong(value);
      } catch (NumberFormatException ignored) {
      }
    }
    return toLong(getFixedString(columnIndex));
  }

  /**
   * A dummy exception thrown when fast byte[] to number parsing fails and no value can be returned.
   * The exact stack trace does not matter because the exception is always caught and is not visible
   * to users.
   */
  private static final NumberFormatException FAST_NUMBER_FAILED = new NumberFormatException() {

    // Override fillInStackTrace to prevent memory leak via Throwable.backtrace hidden field
    // The field is not observable via reflection, however when throwable contains stacktrace, it
    // does
    // hold strong references to user objects (e.g. classes -> classloaders), thus it might lead to
    // OutOfMemory conditions.
    @Override
    public synchronized Throwable fillInStackTrace() {
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
   *         The value must then be parsed by {@link #toLong(String)}.
   */
  private long getFastLong(byte[] bytes) throws NumberFormatException {
    if (bytes.length == 0) {
      throw FAST_NUMBER_FAILED;
    }

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

    while (start < bytes.length) {
      byte b = bytes[start++];
      if (b < '0' || b > '9') {
        throw FAST_NUMBER_FAILED;
      }

      val *= 10;
      val += b - '0';
    }

    if (neg) {
      val = -val;
    }

    return val;
  }

  /**
   * Optimised byte[] to number parser. This code does not handle null values, so the caller must do
   * checkResultSet and handle null values prior to calling this function.
   *
   * @param bytes integer represented as a sequence of ASCII bytes
   * @return The parsed number.
   * @throws NumberFormatException If the number is invalid or the out of range for fast parsing.
   *         The value must then be parsed by {@link #toInt(String)}.
   */
  private int getFastInt(byte[] bytes) throws NumberFormatException {
    if (bytes.length == 0) {
      throw FAST_NUMBER_FAILED;
    }

    int val = 0;
    int start;
    boolean neg;
    if (bytes[0] == '-') {
      neg = true;
      start = 1;
      if (bytes.length == 1 || bytes.length > 10) {
        throw FAST_NUMBER_FAILED;
      }
    } else {
      start = 0;
      neg = false;
      if (bytes.length > 9) {
        throw FAST_NUMBER_FAILED;
      }
    }

    while (start < bytes.length) {
      byte b = bytes[start++];
      if (b < '0' || b > '9') {
        throw FAST_NUMBER_FAILED;
      }

      val *= 10;
      val += b - '0';
    }

    if (neg) {
      val = -val;
    }

    return val;
  }

  /**
   * Optimised byte[] to number parser. This code does not handle null values, so the caller must do
   * checkResultSet and handle null values prior to calling this function.
   *
   * @param bytes integer represented as a sequence of ASCII bytes
   * @return The parsed number.
   * @throws NumberFormatException If the number is invalid or the out of range for fast parsing.
   *         The value must then be parsed by {@link #toBigDecimal(String, int)}.
   */
  private BigDecimal getFastBigDecimal(byte[] bytes) throws NumberFormatException {
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
        if (b == '.') {
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

    if (isBinary(columnIndex)) {
      int col = columnIndex - 1;
      int oid = fields[col].getOID();
      if (oid == Oid.FLOAT4) {
        return ByteConverter.float4(value, 0);
      }
      return (float) readDoubleValue(value, oid, "float");
    }

    return toFloat(getFixedString(columnIndex));
  }

  @Pure
  @Override
  public double getDouble(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getDouble columnIndex: {0}", columnIndex);
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return 0; // SQL NULL
    }

    if (isBinary(columnIndex)) {
      int col = columnIndex - 1;
      int oid = fields[col].getOID();
      if (oid == Oid.FLOAT8) {
        return ByteConverter.float8(value, 0);
      }
      return readDoubleValue(value, oid, "double");
    }

    return toDouble(getFixedString(columnIndex));
  }

  public @Nullable BigDecimal getBigDecimal(
      int columnIndex, int scale) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getBigDecimal columnIndex: {0}", columnIndex);
    return (BigDecimal) getNumeric(columnIndex, scale, false);
  }

  @Pure
  private @Nullable Number getNumeric(
      int columnIndex, int scale, boolean allowNaN) throws SQLException {
    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return null;
    }

    if (isBinary(columnIndex)) {
      int sqlType = getSQLType(columnIndex);
      if (sqlType != Types.NUMERIC && sqlType != Types.DECIMAL) {
        Object obj = internalGetObject(columnIndex, fields[columnIndex - 1]);
        if (obj == null) {
          return null;
        }
        if (obj instanceof Long || obj instanceof Integer || obj instanceof Byte) {
          BigDecimal res = BigDecimal.valueOf(((Number) obj).longValue());
          res = scaleBigDecimal(res, scale);
          return res;
        }
        return toBigDecimal(trimMoney(String.valueOf(obj)), scale);
      } else {
        Number num = ByteConverter.numeric(value);
        if (allowNaN && Double.isNaN(num.doubleValue())) {
          return Double.NaN;
        }

        return num;
      }
    }

    Encoding encoding = connection.getEncoding();
    if (encoding.hasAsciiNumbers()) {
      try {
        BigDecimal res = getFastBigDecimal(value);
        res = scaleBigDecimal(res, scale);
        return res;
      } catch (NumberFormatException ignore) {
      }
    }

    String stringValue = getFixedString(columnIndex);
    if (allowNaN && "NaN".equalsIgnoreCase(stringValue)) {
      return Double.NaN;
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
      // If the data is already binary then just return it
      return value;
    }
    if (fields[columnIndex - 1].getOID() == Oid.BYTEA) {
      return trimBytes(columnIndex, PGbytea.toBytes(value));
    } else {
      return trimBytes(columnIndex, value);
    }
  }

  @Pure
  public java.sql.@Nullable Date getDate(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getDate columnIndex: {0}", columnIndex);
    return getDate(columnIndex, null);
  }

  @Pure
  public @Nullable Time getTime(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getTime columnIndex: {0}", columnIndex);
    return getTime(columnIndex, null);
  }

  @Pure
  public @Nullable Timestamp getTimestamp(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getTimestamp columnIndex: {0}", columnIndex);
    return getTimestamp(columnIndex, null);
  }

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
    try {
      String stringValue = castNonNull(getString(columnIndex));
      return new ByteArrayInputStream(stringValue.getBytes("ASCII"));
    } catch (UnsupportedEncodingException l_uee) {
      throw new PSQLException(GT.tr("The JVM claims not to support the encoding: {0}", "ASCII"),
          PSQLState.UNEXPECTED_ERROR, l_uee);
    }
  }

  @Pure
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
    try {
      String stringValue = castNonNull(getString(columnIndex));
      return new ByteArrayInputStream(stringValue.getBytes("UTF-8"));
    } catch (UnsupportedEncodingException l_uee) {
      throw new PSQLException(GT.tr("The JVM claims not to support the encoding: {0}", "UTF-8"),
          PSQLState.UNEXPECTED_ERROR, l_uee);
    }
  }

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

  @Pure
  public @Nullable String getString(String columnName) throws SQLException {
    return getString(findColumn(columnName));
  }

  @Pure
  @Override
  public boolean getBoolean(String columnName) throws SQLException {
    return getBoolean(findColumn(columnName));
  }

  @Pure
  public byte getByte(String columnName) throws SQLException {
    return getByte(findColumn(columnName));
  }

  @Pure
  public short getShort(String columnName) throws SQLException {
    return getShort(findColumn(columnName));
  }

  @Pure
  public int getInt(String columnName) throws SQLException {
    return getInt(findColumn(columnName));
  }

  @Pure
  public long getLong(String columnName) throws SQLException {
    return getLong(findColumn(columnName));
  }

  @Pure
  public float getFloat(String columnName) throws SQLException {
    return getFloat(findColumn(columnName));
  }

  @Pure
  public double getDouble(String columnName) throws SQLException {
    return getDouble(findColumn(columnName));
  }

  @Pure
  public @Nullable BigDecimal getBigDecimal(String columnName, int scale) throws SQLException {
    return getBigDecimal(findColumn(columnName), scale);
  }

  @Pure
  public byte @Nullable [] getBytes(String columnName) throws SQLException {
    return getBytes(findColumn(columnName));
  }

  @Pure
  public java.sql.@Nullable Date getDate(String columnName) throws SQLException {
    return getDate(findColumn(columnName), null);
  }

  @Pure
  public @Nullable Time getTime(String columnName) throws SQLException {
    return getTime(findColumn(columnName), null);
  }

  @Pure
  public @Nullable Timestamp getTimestamp(String columnName) throws SQLException {
    return getTimestamp(findColumn(columnName), null);
  }

  @Pure
  public @Nullable InputStream getAsciiStream(String columnName) throws SQLException {
    return getAsciiStream(findColumn(columnName));
  }

  @Pure
  public @Nullable InputStream getUnicodeStream(String columnName) throws SQLException {
    return getUnicodeStream(findColumn(columnName));
  }

  @Pure
  public @Nullable InputStream getBinaryStream(String columnName) throws SQLException {
    return getBinaryStream(findColumn(columnName));
  }

  @Pure
  public @Nullable SQLWarning getWarnings() throws SQLException {
    checkClosed();
    return warnings;
  }

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

  public @Nullable String getCursorName() throws SQLException {
    checkClosed();
    return null;
  }

  @Override
  public @Nullable Object getObject(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getObject columnIndex: {0}", columnIndex);
    Field field;

    byte[] value = getRawValue(columnIndex);
    if (value == null) {
      return null;
    }

    field = fields[columnIndex - 1];

    // some fields can be null, mainly from those returned by MetaData methods
    if (field == null) {
      wasNullFlag = true;
      return null;
    }

    Object result = internalGetObject(columnIndex, field);
    if (result != null) {
      return result;
    }

    if (isBinary(columnIndex)) {
      return connection.getObject(getPGType(columnIndex), null, value);
    }
    String stringValue = castNonNull(getString(columnIndex));
    return connection.getObject(getPGType(columnIndex), stringValue, null);
  }

  public @Nullable Object getObject(String columnName) throws SQLException {
    return getObject(findColumn(columnName));
  }

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
    Map<String, Integer> columnNameIndexMap = new HashMap<String, Integer>(fields.length * 2);
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
   * <p>This is used to fix get*() methods on Money fields. It should only be used by those methods!</p>
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

  private @PolyNull String trimMoney(@PolyNull String s) {
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
    Field field = fields[column - 1];
    initSqlType(field);
    return field.getPGType();
  }

  @Pure
  protected int getSQLType(@Positive int column) throws SQLException {
    Field field = fields[column - 1];
    initSqlType(field);
    return field.getSQLType();
  }

  @Pure
  private void initSqlType(Field field) throws SQLException {
    if (field.isTypeInitialized()) {
      return;
    }
    TypeInfo typeInfo = connection.getTypeInfo();
    int oid = field.getOID();
    String pgType = castNonNull(typeInfo.getPGType(oid));
    int sqlType = typeInfo.getSQLType(pgType);
    field.setSQLType(sqlType);
    field.setPGType(pgType);
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
      updateValues = new HashMap<String, Object>((int) (fields.length / 0.75), 0.75f);
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

  public @PolyNull BigDecimal toBigDecimal(@PolyNull String s, int scale) throws SQLException {
    if (s == null) {
      return null;
    }
    BigDecimal val = toBigDecimal(s);
    return scaleBigDecimal(val, scale);
  }

  private BigDecimal scaleBigDecimal(BigDecimal val, int scale) throws PSQLException {
    if (scale == -1) {
      return val;
    }
    try {
      return val.setScale(scale);
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

  /**
   * Converts any numeric binary field to double value. This method does no overflow checking.
   *
   * @param bytes The bytes of the numeric field.
   * @param oid The oid of the field.
   * @param targetType The target type. Used for error reporting.
   * @return The value as double.
   * @throws PSQLException If the field type is not supported numeric type.
   */
  private double readDoubleValue(byte[] bytes, int oid, String targetType) throws PSQLException {
    // currently implemented binary encoded fields
    switch (oid) {
      case Oid.INT2:
        return ByteConverter.int2(bytes, 0);
      case Oid.INT4:
        return ByteConverter.int4(bytes, 0);
      case Oid.INT8:
        // might not fit but there still should be no overflow checking
        return ByteConverter.int8(bytes, 0);
      case Oid.FLOAT4:
        return ByteConverter.float4(bytes, 0);
      case Oid.FLOAT8:
        return ByteConverter.float8(bytes, 0);
      case Oid.NUMERIC:
        return ByteConverter.numeric(bytes).doubleValue();
    }
    throw new PSQLException(GT.tr("Cannot convert the column of type {0} to requested type {1}.",
        Oid.toString(oid), targetType), PSQLState.DATA_TYPE_MISMATCH);
  }

  /**
   * <p>Converts any numeric binary field to long value.</p>
   *
   * <p>This method is used by getByte,getShort,getInt and getLong. It must support a subset of the
   * following java types that use Binary encoding. (fields that use text encoding use a different
   * code path).
   *
   * <code>byte,short,int,long,float,double,BigDecimal,boolean,string</code>.
   * </p>
   *
   * @param bytes The bytes of the numeric field.
   * @param oid The oid of the field.
   * @param minVal the minimum value allowed.
   * @param maxVal the maximum value allowed.
   * @param targetType The target type. Used for error reporting.
   * @return The value as long.
   * @throws PSQLException If the field type is not supported numeric type or if the value is out of
   *         range.
   */
  @Pure
  private long readLongValue(byte[] bytes, int oid, long minVal, long maxVal, String targetType)
      throws PSQLException {
    long val;
    // currently implemented binary encoded fields
    switch (oid) {
      case Oid.INT2:
        val = ByteConverter.int2(bytes, 0);
        break;
      case Oid.INT4:
        val = ByteConverter.int4(bytes, 0);
        break;
      case Oid.INT8:
        val = ByteConverter.int8(bytes, 0);
        break;
      case Oid.FLOAT4:
        val = (long) ByteConverter.float4(bytes, 0);
        break;
      case Oid.FLOAT8:
        val = (long) ByteConverter.float8(bytes, 0);
        break;
      case Oid.NUMERIC:
        Number num = ByteConverter.numeric(bytes);
        if (num instanceof  BigDecimal) {
          val = ((BigDecimal) num).setScale(0 , RoundingMode.DOWN).longValueExact();
        } else {
          val = num.longValue();
        }
        break;
      default:
        throw new PSQLException(
            GT.tr("Cannot convert the column of type {0} to requested type {1}.",
                Oid.toString(oid), targetType),
            PSQLState.DATA_TYPE_MISMATCH);
    }
    if (val < minVal || val > maxVal) {
      throw new PSQLException(GT.tr("Bad value for type {0} : {1}", targetType, val),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return val;
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

  public void updateRef(@Positive int columnIndex, @Nullable Ref x) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateRef(int,Ref)");
  }

  public void updateRef(String columnName, @Nullable Ref x) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateRef(String,Ref)");
  }

  public void updateBlob(@Positive int columnIndex, @Nullable Blob x) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateBlob(int,Blob)");
  }

  public void updateBlob(String columnName, @Nullable Blob x) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateBlob(String,Blob)");
  }

  public void updateClob(@Positive int columnIndex, @Nullable Clob x) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateClob(int,Clob)");
  }

  public void updateClob(String columnName, @Nullable Clob x) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateClob(String,Clob)");
  }

  public void updateArray(@Positive int columnIndex, @Nullable Array x) throws SQLException {
    updateObject(columnIndex, x);
  }

  public void updateArray(String columnName, @Nullable Array x) throws SQLException {
    updateArray(findColumn(columnName), x);
  }

  public <T> @Nullable T getObject(@Positive int columnIndex, Class<T> type) throws SQLException {
    if (type == null) {
      throw new SQLException("type is null");
    }
    int sqlType = getSQLType(columnIndex);
    if (type == BigDecimal.class) {
      if (sqlType == Types.NUMERIC || sqlType == Types.DECIMAL) {
        return type.cast(getBigDecimal(columnIndex));
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == String.class) {
      if (sqlType == Types.CHAR || sqlType == Types.VARCHAR) {
        return type.cast(getString(columnIndex));
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == Boolean.class) {
      if (sqlType == Types.BOOLEAN || sqlType == Types.BIT) {
        boolean booleanValue = getBoolean(columnIndex);
        if (wasNull()) {
          return null;
        }
        return type.cast(booleanValue);
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == Short.class) {
      if (sqlType == Types.SMALLINT) {
        short shortValue = getShort(columnIndex);
        if (wasNull()) {
          return null;
        }
        return type.cast(shortValue);
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == Integer.class) {
      if (sqlType == Types.INTEGER || sqlType == Types.SMALLINT) {
        int intValue = getInt(columnIndex);
        if (wasNull()) {
          return null;
        }
        return type.cast(intValue);
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == Long.class) {
      if (sqlType == Types.BIGINT) {
        long longValue = getLong(columnIndex);
        if (wasNull()) {
          return null;
        }
        return type.cast(longValue);
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == BigInteger.class) {
      if (sqlType == Types.BIGINT) {
        long longValue = getLong(columnIndex);
        if (wasNull()) {
          return null;
        }
        return type.cast(BigInteger.valueOf(longValue));
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == Float.class) {
      if (sqlType == Types.REAL) {
        float floatValue = getFloat(columnIndex);
        if (wasNull()) {
          return null;
        }
        return type.cast(floatValue);
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == Double.class) {
      if (sqlType == Types.FLOAT || sqlType == Types.DOUBLE) {
        double doubleValue = getDouble(columnIndex);
        if (wasNull()) {
          return null;
        }
        return type.cast(doubleValue);
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == Date.class) {
      if (sqlType == Types.DATE) {
        return type.cast(getDate(columnIndex));
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == Time.class) {
      if (sqlType == Types.TIME) {
        return type.cast(getTime(columnIndex));
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == Timestamp.class) {
      if (sqlType == Types.TIMESTAMP
              //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
              || sqlType == Types.TIMESTAMP_WITH_TIMEZONE
      //#endif
      ) {
        return type.cast(getTimestamp(columnIndex));
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == Calendar.class) {
      if (sqlType == Types.TIMESTAMP
              //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
              || sqlType == Types.TIMESTAMP_WITH_TIMEZONE
      //#endif
      ) {
        Timestamp timestampValue = getTimestamp(columnIndex);
        if (timestampValue == null) {
          return null;
        }
        Calendar calendar = Calendar.getInstance(getDefaultCalendar().getTimeZone());
        calendar.setTimeInMillis(timestampValue.getTime());
        return type.cast(calendar);
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == Blob.class) {
      if (sqlType == Types.BLOB || sqlType == Types.BINARY || sqlType == Types.BIGINT) {
        return type.cast(getBlob(columnIndex));
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == Clob.class) {
      if (sqlType == Types.CLOB || sqlType == Types.BIGINT) {
        return type.cast(getClob(columnIndex));
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == java.util.Date.class) {
      if (sqlType == Types.TIMESTAMP) {
        Timestamp timestamp = getTimestamp(columnIndex);
        if (timestamp == null) {
          return null;
        }
        return type.cast(new java.util.Date(timestamp.getTime()));
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == Array.class) {
      if (sqlType == Types.ARRAY) {
        return type.cast(getArray(columnIndex));
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == SQLXML.class) {
      if (sqlType == Types.SQLXML) {
        return type.cast(getSQLXML(columnIndex));
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == UUID.class) {
      return type.cast(getObject(columnIndex));
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
      // JSR-310 support
      //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
    } else if (type == java.time.LocalDate.class) {
      if (sqlType == Types.DATE) {
        Date dateValue = getDate(columnIndex);
        if (dateValue == null) {
          return null;
        }
        long time = dateValue.getTime();
        if (time == PGStatement.DATE_POSITIVE_INFINITY) {
          return type.cast(java.time.LocalDate.MAX);
        }
        if (time == PGStatement.DATE_NEGATIVE_INFINITY) {
          return type.cast(java.time.LocalDate.MIN);
        }
        return type.cast(dateValue.toLocalDate());
      } else if (sqlType == Types.TIMESTAMP) {
        java.time.LocalDateTime localDateTimeValue = getLocalDateTime(columnIndex);
        if (localDateTimeValue == null) {
          return null;
        }
        return type.cast(localDateTimeValue.toLocalDate());
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == java.time.LocalTime.class) {
      if (sqlType == Types.TIME) {
        return type.cast(getLocalTime(columnIndex));
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == java.time.LocalDateTime.class) {
      if (sqlType == Types.TIMESTAMP) {
        return type.cast(getLocalDateTime(columnIndex));
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else if (type == java.time.OffsetDateTime.class) {
      if (sqlType == Types.TIMESTAMP_WITH_TIMEZONE || sqlType == Types.TIMESTAMP) {
        java.time.OffsetDateTime offsetDateTime = getOffsetDateTime(columnIndex);
        return type.cast(offsetDateTime);
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
                PSQLState.INVALID_PARAMETER_VALUE);
      }
      //#endif
    } else if (PGobject.class.isAssignableFrom(type)) {
      Object object;
      if (isBinary(columnIndex)) {
        byte[] byteValue = castNonNull(thisRow, "thisRow").get(columnIndex - 1);
        object = connection.getObject(getPGType(columnIndex), null, byteValue);
      } else {
        object = connection.getObject(getPGType(columnIndex), getString(columnIndex), null);
      }
      return type.cast(object);
    }
    throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, getPGType(columnIndex)),
            PSQLState.INVALID_PARAMETER_VALUE);
  }

  public <T> @Nullable T getObject(String columnLabel, Class<T> type) throws SQLException {
    return getObject(findColumn(columnLabel), type);
  }

  public @Nullable Object getObject(String s, @Nullable Map<String, Class<?>> map) throws SQLException {
    return getObjectImpl(s, map);
  }

  public @Nullable Object getObject(@Positive int i, @Nullable Map<String, Class<?>> map) throws SQLException {
    return getObjectImpl(i, map);
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  public void updateObject(@Positive int columnIndex, @Nullable Object x, java.sql.SQLType targetSqlType,
      int scaleOrLength) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateObject");
  }

  public void updateObject(String columnLabel, @Nullable Object x, java.sql.SQLType targetSqlType,
      int scaleOrLength) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateObject");
  }

  public void updateObject(@Positive int columnIndex, @Nullable Object x, java.sql.SQLType targetSqlType)
      throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateObject");
  }

  public void updateObject(String columnLabel, @Nullable Object x, java.sql.SQLType targetSqlType)
      throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateObject");
  }
  //#endif

  public @Nullable RowId getRowId(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getRowId columnIndex: {0}", columnIndex);
    throw org.postgresql.Driver.notImplemented(this.getClass(), "getRowId(int)");
  }

  public @Nullable RowId getRowId(String columnName) throws SQLException {
    return getRowId(findColumn(columnName));
  }

  public void updateRowId(@Positive int columnIndex, @Nullable RowId x) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateRowId(int, RowId)");
  }

  public void updateRowId(String columnName, @Nullable RowId x) throws SQLException {
    updateRowId(findColumn(columnName), x);
  }

  public int getHoldability() throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "getHoldability()");
  }

  public boolean isClosed() throws SQLException {
    return (rows == null);
  }

  public void updateNString(@Positive int columnIndex, @Nullable String nString) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateNString(int, String)");
  }

  public void updateNString(String columnName, @Nullable String nString) throws SQLException {
    updateNString(findColumn(columnName), nString);
  }

  public void updateNClob(@Positive int columnIndex, @Nullable NClob nClob) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateNClob(int, NClob)");
  }

  public void updateNClob(String columnName, @Nullable NClob nClob) throws SQLException {
    updateNClob(findColumn(columnName), nClob);
  }

  public void updateNClob(@Positive int columnIndex, @Nullable Reader reader) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateNClob(int, Reader)");
  }

  public void updateNClob(String columnName, @Nullable Reader reader) throws SQLException {
    updateNClob(findColumn(columnName), reader);
  }

  public void updateNClob(@Positive int columnIndex, @Nullable Reader reader, long length) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateNClob(int, Reader, long)");
  }

  public void updateNClob(String columnName, @Nullable Reader reader, long length) throws SQLException {
    updateNClob(findColumn(columnName), reader, length);
  }

  public @Nullable NClob getNClob(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getNClob columnIndex: {0}", columnIndex);
    throw org.postgresql.Driver.notImplemented(this.getClass(), "getNClob(int)");
  }

  public @Nullable NClob getNClob(String columnName) throws SQLException {
    return getNClob(findColumn(columnName));
  }

  public void updateBlob(@Positive int columnIndex, @Nullable InputStream inputStream, long length)
      throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(),
        "updateBlob(int, InputStream, long)");
  }

  public void updateBlob(String columnName, @Nullable InputStream inputStream, long length)
      throws SQLException {
    updateBlob(findColumn(columnName), inputStream, length);
  }

  public void updateBlob(@Positive int columnIndex, @Nullable InputStream inputStream) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateBlob(int, InputStream)");
  }

  public void updateBlob(String columnName, @Nullable InputStream inputStream) throws SQLException {
    updateBlob(findColumn(columnName), inputStream);
  }

  public void updateClob(@Positive int columnIndex, @Nullable Reader reader, long length) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateClob(int, Reader, long)");
  }

  public void updateClob(String columnName, @Nullable Reader reader, long length) throws SQLException {
    updateClob(findColumn(columnName), reader, length);
  }

  public void updateClob(@Positive int columnIndex, @Nullable Reader reader) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "updateClob(int, Reader)");
  }

  public void updateClob(String columnName, @Nullable Reader reader) throws SQLException {
    updateClob(findColumn(columnName), reader);
  }

  @Pure
  public @Nullable SQLXML getSQLXML(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getSQLXML columnIndex: {0}", columnIndex);
    String data = getString(columnIndex);
    if (data == null) {
      return null;
    }

    return new PgSQLXML(connection, data);
  }

  public @Nullable SQLXML getSQLXML(String columnName) throws SQLException {
    return getSQLXML(findColumn(columnName));
  }

  public void updateSQLXML(@Positive int columnIndex, @Nullable SQLXML xmlObject) throws SQLException {
    updateValue(columnIndex, xmlObject);
  }

  public void updateSQLXML(String columnName, @Nullable SQLXML xmlObject) throws SQLException {
    updateSQLXML(findColumn(columnName), xmlObject);
  }

  public @Nullable String getNString(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getNString columnIndex: {0}", columnIndex);
    throw org.postgresql.Driver.notImplemented(this.getClass(), "getNString(int)");
  }

  public @Nullable String getNString(String columnName) throws SQLException {
    return getNString(findColumn(columnName));
  }

  public @Nullable Reader getNCharacterStream(@Positive int columnIndex) throws SQLException {
    connection.getLogger().log(Level.FINEST, "  getNCharacterStream columnIndex: {0}", columnIndex);
    throw org.postgresql.Driver.notImplemented(this.getClass(), "getNCharacterStream(int)");
  }

  public @Nullable Reader getNCharacterStream(String columnName) throws SQLException {
    return getNCharacterStream(findColumn(columnName));
  }

  public void updateNCharacterStream(@Positive int columnIndex,
      @Nullable Reader x, int length) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(),
        "updateNCharacterStream(int, Reader, int)");
  }

  public void updateNCharacterStream(String columnName,
      @Nullable Reader x, int length) throws SQLException {
    updateNCharacterStream(findColumn(columnName), x, length);
  }

  public void updateNCharacterStream(@Positive int columnIndex,
      @Nullable Reader x) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(),
        "updateNCharacterStream(int, Reader)");
  }

  public void updateNCharacterStream(String columnName,
      @Nullable Reader x) throws SQLException {
    updateNCharacterStream(findColumn(columnName), x);
  }

  public void updateNCharacterStream(@Positive int columnIndex,
      @Nullable Reader x, long length) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(),
        "updateNCharacterStream(int, Reader, long)");
  }

  public void updateNCharacterStream(String columnName,
      @Nullable Reader x, long length) throws SQLException {
    updateNCharacterStream(findColumn(columnName), x, length);
  }

  public void updateCharacterStream(@Positive int columnIndex,
      @Nullable Reader reader, long length)
      throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(),
        "updateCharaceterStream(int, Reader, long)");
  }

  public void updateCharacterStream(String columnName,
      @Nullable Reader reader, long length)
      throws SQLException {
    updateCharacterStream(findColumn(columnName), reader, length);
  }

  public void updateCharacterStream(@Positive int columnIndex,
      @Nullable Reader reader) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(),
        "updateCharaceterStream(int, Reader)");
  }

  public void updateCharacterStream(String columnName,
      @Nullable Reader reader) throws SQLException {
    updateCharacterStream(findColumn(columnName), reader);
  }

  public void updateBinaryStream(@Positive int columnIndex,
      @Nullable InputStream inputStream, long length)
      throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(),
        "updateBinaryStream(int, InputStream, long)");
  }

  public void updateBinaryStream(String columnName,
      @Nullable InputStream inputStream, long length)
      throws SQLException {
    updateBinaryStream(findColumn(columnName), inputStream, length);
  }

  public void updateBinaryStream(@Positive int columnIndex,
      @Nullable InputStream inputStream) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(),
        "updateBinaryStream(int, InputStream)");
  }

  public void updateBinaryStream(String columnName,
      @Nullable InputStream inputStream) throws SQLException {
    updateBinaryStream(findColumn(columnName), inputStream);
  }

  public void updateAsciiStream(@Positive int columnIndex,
      @Nullable InputStream inputStream, long length)
      throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(),
        "updateAsciiStream(int, InputStream, long)");
  }

  public void updateAsciiStream(String columnName,
      @Nullable InputStream inputStream, long length)
      throws SQLException {
    updateAsciiStream(findColumn(columnName), inputStream, length);
  }

  public void updateAsciiStream(@Positive int columnIndex,
      @Nullable InputStream inputStream) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(),
        "updateAsciiStream(int, InputStream)");
  }

  public void updateAsciiStream(String columnName,
      @Nullable InputStream inputStream) throws SQLException {
    updateAsciiStream(findColumn(columnName), inputStream);
  }

  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isAssignableFrom(getClass());
  }

  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isAssignableFrom(getClass())) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
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
