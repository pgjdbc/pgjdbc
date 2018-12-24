/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.util.PGobject;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
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
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInput;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
//#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
import java.time.LocalDateTime;
import java.time.LocalTime;
//#endif
import java.util.Calendar;


/**
 * Abstraction used to access values of various types.
 * This allows unified access across underlying {@link CallableStatement},
 * {@link ResultSet}, and {@link SQLInput}.
 * <p>
 * This is used to be able to create objects, including user-defined data types,
 * from various sources.
 * </p>
 *
 * @see ValueAccessHelper#getObject(org.postgresql.udt.ValueAccess, int, java.lang.String, java.lang.Class, org.postgresql.udt.UdtMap, org.postgresql.util.PSQLState)
 */
public interface ValueAccess {

  /**
   * Determines whether the {@link #getBytes()} or {@link #getString()} method should
   * be preferred when either is an option.
   *
   * @return {@code true} when binary representation is preferred over textual representation
   */
  boolean isBinary();

  /**
   * Gets the {@link String} representation of this value.
   * <p>
   * Required by all implementations.
   * </p>
   *
   * @return {@code null} if this value is null or {@link String} of the value.
   *
   * @throws SQLException if a database access error occurs
   *
   * @see  CallableStatement#getString(java.lang.String)
   * @see  CallableStatement#getString(int)
   * @see  ResultSet#getString(java.lang.String)
   * @see  ResultSet#getString(int)
   * @see  SQLInput#readString()
   */
  String getString() throws SQLException;

  /**
   * Gets the {@code boolean} representation of this value.
   *
   * @return {@code false} if this value is null or {@code boolean} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getBoolean(java.lang.String)
   * @see  CallableStatement#getBoolean(int)
   * @see  ResultSet#getBoolean(java.lang.String)
   * @see  ResultSet#getBoolean(int)
   * @see  SQLInput#readBoolean()
   */
  boolean getBoolean() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@code byte} representation of this value.
   *
   * @return {@code 0} if this value is null or {@code byte} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getByte(java.lang.String)
   * @see  CallableStatement#getByte(int)
   * @see  ResultSet#getByte(java.lang.String)
   * @see  ResultSet#getByte(int)
   * @see  SQLInput#readByte()
   */
  byte getByte() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@code short} representation of this value.
   *
   * @return {@code 0} if this value is null or {@code short} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getShort(java.lang.String)
   * @see  CallableStatement#getShort(int)
   * @see  ResultSet#getShort(java.lang.String)
   * @see  ResultSet#getShort(int)
   * @see  SQLInput#readShort()
   */
  short getShort() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@code int} representation of this value.
   *
   * @return {@code 0} if this value is null or {@code int} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getInt(java.lang.String)
   * @see  CallableStatement#getInt(int)
   * @see  ResultSet#getInt(java.lang.String)
   * @see  ResultSet#getInt(int)
   * @see  SQLInput#readInt()
   */
  int getInt() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@code long} representation of this value.
   *
   * @return {@code 0} if this value is null or {@code long} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getLong(java.lang.String)
   * @see  CallableStatement#getLong(int)
   * @see  ResultSet#getLong(java.lang.String)
   * @see  ResultSet#getLong(int)
   * @see  SQLInput#readLong()
   */
  long getLong() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@code float} representation of this value.
   *
   * @return {@code 0} if this value is null or {@code float} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getFloat(java.lang.String)
   * @see  CallableStatement#getFloat(int)
   * @see  ResultSet#getFloat(java.lang.String)
   * @see  ResultSet#getFloat(int)
   * @see  SQLInput#readFloat()
   */
  float getFloat() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@code double} representation of this value.
   *
   * @return {@code 0} if this value is null or {@code double} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getDouble(java.lang.String)
   * @see  CallableStatement#getDouble(int)
   * @see  ResultSet#getDouble(java.lang.String)
   * @see  ResultSet#getDouble(int)
   * @see  SQLInput#readDouble()
   */
  double getDouble() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@link BigDecimal} representation of this value.
   *
   * @return {@code null} if this value is null or {@link BigDecimal} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getBigDecimal(java.lang.String)
   * @see  CallableStatement#getBigDecimal(int)
   * @see  ResultSet#getBigDecimal(java.lang.String)
   * @see  ResultSet#getBigDecimal(int)
   * @see  SQLInput#readBigDecimal()
   */
  BigDecimal getBigDecimal() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@code byte[]} representation of this value.
   *
   * @return {@code null} if this value is null or {@code byte[]} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getBytes(java.lang.String)
   * @see  CallableStatement#getBytes(int)
   * @see  ResultSet#getBytes(java.lang.String)
   * @see  ResultSet#getBytes(int)
   * @see  SQLInput#readBytes()
   */
  byte[] getBytes() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@link Date} representation of this value.
   *
   * @return {@code null} if this value is null or {@link Date} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getDate(java.lang.String)
   * @see  CallableStatement#getDate(int)
   * @see  ResultSet#getDate(java.lang.String)
   * @see  ResultSet#getDate(int)
   * @see  SQLInput#readDate()
   */
  Date getDate() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@link Time} representation of this value.
   *
   * @return {@code null} if this value is null or {@link Time} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getTime(java.lang.String)
   * @see  CallableStatement#getTime(int)
   * @see  ResultSet#getTime(java.lang.String)
   * @see  ResultSet#getTime(int)
   * @see  SQLInput#readTime()
   */
  Time getTime() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@link Timestamp} representation of this value.
   *
   * @return {@code null} if this value is null or {@link Timestamp} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getTimestamp(java.lang.String)
   * @see  CallableStatement#getTimestamp(int)
   * @see  ResultSet#getTimestamp(java.lang.String)
   * @see  ResultSet#getTimestamp(int)
   * @see  SQLInput#readTimestamp()
   */
  Timestamp getTimestamp() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the characters of this value as a {@link Reader}.
   *
   * @return {@code null} if this value is null or a {@link Reader} of the characters of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getCharacterStream(java.lang.String)
   * @see  CallableStatement#getCharacterStream(int)
   * @see  ResultSet#getCharacterStream(java.lang.String)
   * @see  ResultSet#getCharacterStream(int)
   * @see  SQLInput#readCharacterStream()
   */
  Reader getCharacterStream() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the ASCII characters of this value as an {@link InputStream}.
   *
   * @return {@code null} if this value is null or an {@link InputStream} of the ASCII characters of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  ResultSet#getAsciiStream(java.lang.String)
   * @see  ResultSet#getAsciiStream(int)
   * @see  SQLInput#readAsciiStream()
   */
  InputStream getAsciiStream() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the binary representation of this value as an {@link InputStream}.
   *
   * @return {@code null} if this value is null or an {@link InputStream} of the binary representation of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  ResultSet#getBinaryStream(java.lang.String)
   * @see  ResultSet#getBinaryStream(int)
   * @see  SQLInput#readBinaryStream()
   */
  InputStream getBinaryStream() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@link Object} representation of this value.
   * This may be affected by the current {@link UdtMap user-defined data type mapping}.
   * <p>
   * Required by all implementations.
   * </p>
   *
   * @param udtMap the current user-defined data types
   * @return {@code null} if this value is null or {@link Object} of the value.
   *
   * @throws SQLException if a database access error occurs
   *
   * @see  CallableStatement#getObject(java.lang.String)
   * @see  CallableStatement#getObject(int)
   * @see  ResultSet#getObject(java.lang.String)
   * @see  ResultSet#getObject(int)
   * @see  SQLInput#readObject()
   */
  // TODO: Does getObject belong on the ValueAccess interface, or is it part of the SQLInput implementations?
  Object getObject(UdtMap udtMap) throws SQLException;

  /**
   * Gets the {@link Ref} representation of this value.
   *
   * @return {@code null} if this value is null or {@link Ref} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getRef(java.lang.String)
   * @see  CallableStatement#getRef(int)
   * @see  ResultSet#getRef(java.lang.String)
   * @see  ResultSet#getRef(int)
   * @see  SQLInput#readRef()
   */
  Ref getRef() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@link Blob} representation of this value.
   *
   * @return {@code null} if this value is null or {@link Blob} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getBlob(java.lang.String)
   * @see  CallableStatement#getBlob(int)
   * @see  ResultSet#getBlob(java.lang.String)
   * @see  ResultSet#getBlob(int)
   * @see  SQLInput#readBlob()
   */
  Blob getBlob() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@link Clob} representation of this value.
   *
   * @return {@code null} if this value is null or {@link Clob} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getClob(java.lang.String)
   * @see  CallableStatement#getClob(int)
   * @see  ResultSet#getClob(java.lang.String)
   * @see  ResultSet#getClob(int)
   * @see  SQLInput#readClob()
   */
  Clob getClob() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@link Array} representation of this value.
   *
   * @return {@code null} if this value is null or {@link Array} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getArray(java.lang.String)
   * @see  CallableStatement#getArray(int)
   * @see  ResultSet#getArray(java.lang.String)
   * @see  ResultSet#getArray(int)
   * @see  SQLInput#readArray()
   */
  Array getArray() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Determines if the last call to any {@code get*()} method represented a {@code null} value.
   * <p>
   * Required by all implementations.
   * </p>
   * <p>
   * This must only be called after calls to one of the {@code get*()} methods.  However, for
   * efficiency, not all implementations enforce this requirement. (TODO: requirement enforcement?)
   * </p>
   *
   * @return whether the last call to a {@code get*()} method represented a {@code null} value.
   *
   * @throws SQLException if a database access error occurs
   *
   * @see  CallableStatement#wasNull()
   * @see  ResultSet#wasNull()
   * @see  SQLInput#wasNull()
   */
  boolean wasNull() throws SQLException;

  /**
   * Gets the {@link URL} representation of this value.
   *
   * @return {@code null} if this value is null or {@link URL} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getURL(java.lang.String)
   * @see  CallableStatement#getURL(int)
   * @see  ResultSet#getURL(java.lang.String)
   * @see  ResultSet#getURL(int)
   * @see  SQLInput#readURL()
   */
  URL getURL() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@link NClob} representation of this value.
   *
   * @return {@code null} if this value is null or {@link NClob} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getNClob(java.lang.String)
   * @see  CallableStatement#getNClob(int)
   * @see  ResultSet#getNClob(java.lang.String)
   * @see  ResultSet#getNClob(int)
   * @see  SQLInput#readNClob()
   */
  NClob getNClob() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the Unicode {@link String} representation of this value.
   *
   * @return {@code null} if this value is null or {@link String} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getNString(java.lang.String)
   * @see  CallableStatement#getNString(int)
   * @see  ResultSet#getNString(java.lang.String)
   * @see  ResultSet#getNString(int)
   * @see  SQLInput#readNString()
   */
  String getNString() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@link SQLXML} representation of this value.
   *
   * @return {@code null} if this value is null or {@link SQLXML} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getSQLXML(java.lang.String)
   * @see  CallableStatement#getSQLXML(int)
   * @see  ResultSet#getSQLXML(java.lang.String)
   * @see  ResultSet#getSQLXML(int)
   * @see  SQLInput#readSQLXML()
   */
  SQLXML getSQLXML() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@link RowId} representation of this value.
   *
   * @return {@code null} if this value is null or {@link RowId} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see  CallableStatement#getRowId(java.lang.String)
   * @see  CallableStatement#getRowId(int)
   * @see  ResultSet#getRowId(java.lang.String)
   * @see  ResultSet#getRowId(int)
   * @see  SQLInput#readRowId()
   */
  RowId getRowId() throws SQLException, SQLFeatureNotSupportedException;

  // TODO: Does getPGobject belong on the ValueAcces interface, or is it part of the SQLInput implementations?
  //       Or should there be a PGobectValueAccess implementation, as PGobject acts like base types?
  //       Or both?
  // TODO: This would get the PGobject always, even when there are user-defined data types for it, just like getInt does.
  //       PGobject should behave like base types - below the level of user-defined data types.
  // TODO: Javadocs if will stay here
  PGobject getPGobject(String pgType) throws SQLException, SQLFeatureNotSupportedException;

  // TODO: Does getPGobject belong on the ValueAcces interface, or is it part of the SQLInput implementations?
  //       Or should there be a PGobectValueAccess implementation, as PGobject acts like base types?
  //       Or both?
  // TODO: This would get the PGobject always, even when there are user-defined data types for it, just like getInt does.
  //       PGobject should behave like base types - below the level of user-defined data types.
  // TODO: Javadocs if will stay here
  <T extends PGobject> T getPGobject(Class<T> type) throws SQLException, SQLFeatureNotSupportedException;

  /* *
   * Required by all implementations.
   */
  /* TODO: Required?
  // TODO: Does getObject belong on the ValueAcces interface, or is it part of the SQLInput implementations?
  <T> T getObjectCustomType(UdtMap udtMap, String type, Class<? extends T> customType) throws SQLException;
   */

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  /**
   * Gets the {@link LocalDateTime} representation of this value.
   * <p>
   * This is used by the implementation of {@link ValueAccessHelper#getObject(org.postgresql.udt.ValueAccess, int, java.lang.String, java.lang.Class, org.postgresql.udt.UdtMap, org.postgresql.util.PSQLState)}.
   * </p>
   *
   * @return {@code null} if this value is null or {@link LocalDateTime} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see ValueAccessHelper#getObject(org.postgresql.udt.ValueAccess, int, java.lang.String, java.lang.Class, org.postgresql.udt.UdtMap, org.postgresql.util.PSQLState)
   */
  // TODO: Does this belong here?  It's part of the implementation of ValueAccessHelper
  LocalDateTime getLocalDateTime() throws SQLException, SQLFeatureNotSupportedException;

  /**
   * Gets the {@link LocalTime} representation of this value.
   * <p>
   * This is used by the implementation of {@link ValueAccessHelper#getObject(org.postgresql.udt.ValueAccess, int, java.lang.String, java.lang.Class, org.postgresql.udt.UdtMap, org.postgresql.util.PSQLState)}.
   * </p>
   *
   * @return {@code null} if this value is null or {@link LocalTime} of the value.
   *
   * @throws SQLException if a database access error occurs
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support
   *            this method
   *
   * @see ValueAccessHelper#getObject(org.postgresql.udt.ValueAccess, int, java.lang.String, java.lang.Class, org.postgresql.udt.UdtMap, org.postgresql.util.PSQLState)
   */
  // TODO: Does this belong here?  It's part of the implementation of ValueAccessHelper
  LocalTime getLocalTime() throws SQLException, SQLFeatureNotSupportedException;
  //#endif

  /**
   * Gets the default {@link Calendar} for this value access.
   * <p>
   * This is used by the implementation of {@link ValueAccessHelper#getObject(org.postgresql.udt.ValueAccess, int, java.lang.String, java.lang.Class, org.postgresql.udt.UdtMap, org.postgresql.util.PSQLState)}.
   * </p>
   *
   * @return the calendar to use for date/time handling when no other calendar specified
   *
   * @see ValueAccessHelper#getObject(org.postgresql.udt.ValueAccess, int, java.lang.String, java.lang.Class, org.postgresql.udt.UdtMap, org.postgresql.util.PSQLState)
   */
  // TODO: Does this belong here?  It's part of the implementation of ValueAccessHelper
  Calendar getDefaultCalendar();

  /**
   * Gets the {@link Object} representation of this value in the requested type.
   * This may be affected by the current {@link UdtMap user-defined data type mappings}.
   * <p>
   * Required by all implementations.
   * </p>
   *
   * @param <T> the type of the class modeled by this Class object
   * @param type Class representing the Java data type to convert the attribute to.
   * @param udtMap the current user-defined data types
   *
   * @return {@code null} if this value is null or {@link Object} of the value in the requested type.
   *
   * @throws SQLException if a database access error occurs or unable to convert
   *         to the requested type
   *
   * @see  CallableStatement#getObject(java.lang.String, java.lang.Class)
   * @see  CallableStatement#getObject(int, java.lang.Class)
   * @see  ResultSet#getObject(java.lang.String, java.lang.Class)
   * @see  ResultSet#getObject(int, java.lang.Class)
   * @see  SQLInput#readObject(java.lang.Class)
   */
  // TODO: Does getObject belong on the ValueAcces interface, or is it part of the SQLInput implementations?
  <T> T getObject(Class<T> type, UdtMap udtMap) throws SQLException;

  // Note: getObject(Map) not here because it is not required by SQLInput

  /**
   * Gets a {@link SingleAttributeSQLInput} that retrieves it input from the same
   * source as this {@link ValueAccess}.
   *
   * @param udtMap the current {@link UdtMap user-defined data type mapping}.
   *
   * @return the {@link SQLInput} for reading single-attribute user-defined data types
   */
  SingleAttributeSQLInput getSQLInput(UdtMap udtMap);
}
