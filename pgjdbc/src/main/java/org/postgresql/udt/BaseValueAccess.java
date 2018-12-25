/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.Driver;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgBlob;
import org.postgresql.jdbc.PgClob;
import org.postgresql.jdbc.PgResultSet;
import org.postgresql.jdbc.PgSQLXML;
import org.postgresql.jdbc.TimestampUtils;
import org.postgresql.util.AsciiStream;
import org.postgresql.util.GT;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.ByteArrayInputStream;
import java.io.CharArrayReader;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
//#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
import java.time.LocalDateTime;
import java.time.LocalTime;
//#endif
import java.util.Calendar;
import java.util.TimeZone;


/**
 * Base implementation of {@link ValueAccess}.
 */
// TODO: Consider renaming "PgValue"
public abstract class BaseValueAccess implements ValueAccess {

  protected final BaseConnection connection;
  // TODO: If we can get PgResultSet to use CANNOT_COERCE, this field will not be necessary.
  //       Likewise, if we choose to follow its lead of using PSQLState.DATA_TYPE_MISMATCH
  protected final PSQLState conversionNotSupported;

  private TimeZone defaultTimeZone;

  public BaseValueAccess(BaseConnection connection, PSQLState conversionNotSupported) {
    this.connection = connection;
    this.conversionNotSupported = conversionNotSupported;
  }

  public BaseValueAccess(BaseConnection connection) {
    // TODO: Should this be PSQLState.DATA_TYPE_MISMATCH like PgResultSet?
    this(connection, PSQLState.CANNOT_COERCE);
  }

  protected abstract int getOid();

  protected String getPGType() throws SQLException {
    return connection.getTypeInfo().getPGType(getOid());
  }

  protected int getSQLType() throws SQLException {
    return connection.getTypeInfo().getSQLType(getOid());
  }

  /**
   * {@inheritDoc}
   * <p>
   * Defaults to false, favoring the string representation because all types are
   * required to be convertible to {@link String}, whereas not all types are convertible
   * to {@code byte[]}.
   * </p>
   */
  @Override
  public boolean isBinary() {
    return false;
  }

  @Override
  public abstract String getString() throws SQLException;

  @Override
  public boolean getBoolean() throws SQLException, SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
        GT.tr("conversion to {0} from {1} not supported",
            "boolean", getPGType()),
        conversionNotSupported.getState());
  }

  @Override
  public byte getByte() throws SQLException, SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
        GT.tr("conversion to {0} from {1} not supported",
            "byte", getPGType()),
        conversionNotSupported.getState());
  }

  @Override
  public short getShort() throws SQLException, SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
        GT.tr("conversion to {0} from {1} not supported",
            "short", getPGType()),
        conversionNotSupported.getState());
  }

  @Override
  public int getInt() throws SQLException, SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
        GT.tr("conversion to {0} from {1} not supported",
            "int", getPGType()),
        conversionNotSupported.getState());
  }

  @Override
  public long getLong() throws SQLException, SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
        GT.tr("conversion to {0} from {1} not supported",
            "long", getPGType()),
        conversionNotSupported.getState());
  }

  @Override
  public float getFloat() throws SQLException, SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
        GT.tr("conversion to {0} from {1} not supported",
            "float", getPGType()),
        conversionNotSupported.getState());
  }

  @Override
  public double getDouble() throws SQLException, SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
        GT.tr("conversion to {0} from {1} not supported",
            "double", getPGType()),
        conversionNotSupported.getState());
  }

  @Override
  public BigDecimal getBigDecimal() throws SQLException, SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
        GT.tr("conversion to {0} from {1} not supported",
            BigDecimal.class.getName(), getPGType()),
        conversionNotSupported.getState());
  }

  @Override
  public byte[] getBytes() throws SQLException, SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
        GT.tr("conversion to {0} from {1} not supported",
            "byte[]", getPGType()),
        conversionNotSupported.getState());
  }

  @Override
  public Date getDate() throws SQLException, SQLFeatureNotSupportedException {
    return getDate(null);
  }

  @Override
  public Date getDate(Calendar cal) throws SQLException, SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
        GT.tr("conversion to {0} from {1} not supported",
            Date.class.getName(), getPGType()),
        conversionNotSupported.getState());
  }

  @Override
  public Time getTime() throws SQLException, SQLFeatureNotSupportedException {
    return getTime(null);
  }

  @Override
  public Time getTime(Calendar cal) throws SQLException, SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
        GT.tr("conversion to {0} from {1} not supported",
            Time.class.getName(), getPGType()),
        conversionNotSupported.getState());
  }

  @Override
  public Timestamp getTimestamp() throws SQLException, SQLFeatureNotSupportedException {
    return getTimestamp(null);
  }

  @Override
  public Timestamp getTimestamp(Calendar cal) throws SQLException, SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
        GT.tr("conversion to {0} from {1} not supported",
            Timestamp.class.getName(), getPGType()),
        conversionNotSupported.getState());
  }

  /**
   * {@inheritDoc}
   * <p>
   * This default implementation creates a stream from {@link #getString()}.
   * </p>
   *
   * @see #getString()
   */
  @Override
  public Reader getCharacterStream() throws SQLException {
    String s = getString();
    if (s != null) {
      return new CharArrayReader(s.toCharArray());
    }
    return null;
  }

  /**
   * {@inheritDoc}
   * <p>
   * This default implementation creates an ASCII stream from {@link #getString()}.
   * </p>
   *
   * @see #getString()
   * @see AsciiStream
   */
  @Override
  public InputStream getAsciiStream() throws SQLException {
    String s = getString();
    if (s != null) {
      return AsciiStream.getAsciiStream(s);
    }
    return null;
  }

  /**
   * {@inheritDoc}
   * <p>
   * This default implementation creates a stream from {@link #getBytes()}, which
   * is not necessarily implemented and might throw {@link SQLFeatureNotSupportedException}.
   * </p>
   *
   * @see #getBytes()
   */
  @Override
  public InputStream getBinaryStream() throws SQLException, SQLFeatureNotSupportedException {
    byte[] b = getBytes();
    if (b != null) {
      return new ByteArrayInputStream(b);
    }
    return null;
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implemented via {@link ValueAccessHelper#getObject(org.postgresql.udt.ValueAccess, int, java.lang.String, java.lang.Class, org.postgresql.udt.UdtMap, org.postgresql.util.PSQLState)}
   * </p>
   *
   * @see ValueAccessHelper#getObject(org.postgresql.udt.ValueAccess, int, java.lang.String, java.lang.Class, org.postgresql.udt.UdtMap, org.postgresql.util.PSQLState)
   */
  @Override
  public Object getObject(UdtMap udtMap) throws SQLException {
    return ValueAccessHelper.getObject(connection, ResultSet.TYPE_FORWARD_ONLY, this, getSQLType(), getPGType(), udtMap, conversionNotSupported);
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getRef(int)
   */
  @Override
  public Ref getRef() throws SQLException, SQLFeatureNotSupportedException {
    // The backend doesn't yet have SQL3 REF types
    throw org.postgresql.Driver.notImplemented(this.getClass(), "getRef()");
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getBlob(int)
   */
  @Override
  public Blob getBlob() throws SQLException, SQLFeatureNotSupportedException {
    long oid = getLong();
    return wasNull() ? null : new PgBlob(connection, oid);
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getClob(int)
   */
  @Override
  public Clob getClob() throws SQLException, SQLFeatureNotSupportedException {
    long oid = getLong();
    return wasNull() ? null : new PgClob(connection, oid);
  }

  @Override
  public Array getArray() throws SQLException, SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
      GT.tr("conversion to {0} from {1} not supported",
          Array.class.getName(), getPGType()),
      conversionNotSupported.getState());
  }

  @Override
  public abstract boolean wasNull() throws SQLException;

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getURL(int)
   */
  @Override
  public URL getURL() throws SQLException, SQLFeatureNotSupportedException {
    throw Driver.notImplemented(this.getClass(), "getURL()");
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getNClob(int)
   */
  @Override
  public NClob getNClob() throws SQLException, SQLFeatureNotSupportedException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "getNClob()");
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getNString(int)
   */
  @Override
  public String getNString() throws SQLException, SQLFeatureNotSupportedException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "getNString()");
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getSQLXML(int)
   */
  @Override
  public SQLXML getSQLXML() throws SQLException {
    String data = getString();
    return (data == null) ? null : new PgSQLXML(connection, data);
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getRowId(int)
   */
  @Override
  public RowId getRowId() throws SQLException, SQLFeatureNotSupportedException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "getRowId()");
  }

  @Override
  public PGobject getPGobject(String pgType) throws SQLException, SQLFeatureNotSupportedException {
    if (isBinary()) {
      byte[] byteValue = getBytes();
      return (byteValue == null) ? null : connection.getObject(pgType, null, byteValue);
    }
    String value = getString();
    return (value == null) ? null : connection.getObject(pgType, value, null);
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getPGobject(int, java.lang.Class)
   */
  @Override
  public <T extends PGobject> T getPGobject(Class<T> type) throws SQLException {
    // TODO: custom types here?  Or are PGobject accessed like other base (non-custom) types (getInt, getTime, ...)?
    PGobject object;
    if (isBinary()) {
      byte[] byteValue = getBytes();
      object = (byteValue == null) ? null : connection.getObject(getPGType(), null, byteValue);
    } else {
      String value = getString();
      object = (value == null) ? null : connection.getObject(getPGType(), value, null);
    }
    // TODO: Check if assignable first and make a more appropriate SQLException?
    return type.cast(object);
  }

  /* TODO: Required?
  @Override
  public <T> T getObjectCustomType(UdtMap udtMap, String type, Class<? extends T> customType) throws SQLException {
    // TODO: This is required for all value access types
    throw new SQLFeatureNotSupportedException(
      GT.tr("conversion to {0} from {1} not supported",
          customType.getName(), getPGType()),
      conversionNotSupported.getState());
  }
   */

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getCalendar(int)
   */
  @Override
  public Calendar getCalendar() throws SQLException, SQLFeatureNotSupportedException {
    Timestamp timestampValue = getTimestamp();
    if (timestampValue == null) {
      return null;
    }
    Calendar calendar = Calendar.getInstance(getDefaultCalendar().getTimeZone());
    calendar.setTimeInMillis(timestampValue.getTime());
    return calendar;
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  @Override
  public LocalDateTime getLocalDateTime() throws SQLException, SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
      GT.tr("conversion to {0} from {1} not supported",
          LocalDateTime.class.getName(), getPGType()),
      conversionNotSupported.getState());
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getLocalTime(int)
   */
  // TODO: Redundant with PgResultSet
  @Override
  public LocalTime getLocalTime() throws SQLException, SQLFeatureNotSupportedException {
    // TODO: Does isBinary() make sense for BaseValueAccess?
    // TODO: Should this just be on a TimeValueAccess implementation?
    if (isBinary()) {
      int oid = getOid();
      if (oid == Oid.TIME) {
        byte[] bytes = getBytes();
        if (bytes == null) {
          return null;
        }
        return connection.getTimestampUtils().toLocalTimeBin(bytes);
      } else {
        throw new PSQLException(
            GT.tr("Cannot convert the column of type {0} to requested type {1}.",
                Oid.toString(oid), "time"),
            PSQLState.DATA_TYPE_MISMATCH);
      }
    }

    // TODO: Should this just be on the StringValueAccess implementation?
    String string = getString();
    return (string == null) ? null : connection.getTimestampUtils().toLocalTime(string);
  }
  //#endif

  protected Calendar getDefaultCalendar() {
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
   * {@inheritDoc}
   * <p>
   * Implemented via {@link ValueAccessHelper#getObject(org.postgresql.udt.ValueAccess, int, java.lang.String, java.lang.Class, org.postgresql.udt.UdtMap, org.postgresql.util.PSQLState)}.
   * </p>
   *
   * @see ValueAccessHelper#getObject(org.postgresql.udt.ValueAccess, int, java.lang.String, java.lang.Class, org.postgresql.udt.UdtMap, org.postgresql.util.PSQLState)
   */
  @Override
  public <T> T getObject(Class<T> type, UdtMap udtMap) throws SQLException {
    return ValueAccessHelper.getObject(this, getSQLType(), getPGType(), type, udtMap, conversionNotSupported);
  }

  /**
   * {@inheritDoc}
   *
   * @see ValueAccessSQLInput
   */
  @Override
  public SingleAttributeSQLInput getSQLInput(UdtMap udtMap) {
    return new ValueAccessSQLInput(this, udtMap);
  }
}
