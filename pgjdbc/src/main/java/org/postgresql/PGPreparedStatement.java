/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.List;

public interface PGPreparedStatement extends PGStatement, PreparedStatement {
  /**
   * @param parameterName the name of the parameter to be bound
   * @param sqlType       the SQL type code defined in <code>java.sql.Types</code>
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setNull(int, int)
   */
  void setNull(String parameterName, int sqlType) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setURL(int, java.net.URL)
   */
  void setURL(String parameterName, java.net.URL x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setBoolean(int, boolean)
   */
  void setBoolean(String parameterName, boolean x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setByte(int, byte)
   */
  void setByte(String parameterName, byte x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setShort(int, short)
   */
  void setShort(String parameterName, short x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setInt(int, int)
   */
  void setInt(String parameterName, int x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setLong(int, long)
   */
  void setLong(String parameterName, long x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setFloat(int, float)
   */
  void setFloat(String parameterName, float x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setDouble(int, double)
   */
  void setDouble(String parameterName, double x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setBigDecimal(int, BigDecimal)
   */
  void setBigDecimal(String parameterName, BigDecimal x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setString(int, String)
   */
  void setString(String parameterName, String x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setBytes(int, byte[])
   */
  void setBytes(String parameterName, byte[] x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setDate(int, Date)
   */
  void setDate(String parameterName, java.sql.Date x)
      throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setTime(int, Time)
   */
  void setTime(String parameterName, java.sql.Time x)
      throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setTimestamp(int, Timestamp)
   */
  void setTimestamp(String parameterName, java.sql.Timestamp x)
      throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @param length        the number of bytes in the stream
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setAsciiStream(int, InputStream, int)
   */
  void setAsciiStream(String parameterName, java.io.InputStream x, int length)
      throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @param length        the number of bytes in the stream
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setUnicodeStream(int, InputStream, int)
   * @deprecated Use {@code setCharacterStream}
   */
  @Deprecated
  void setUnicodeStream(String parameterName, java.io.InputStream x,
      int length) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @param length        the number of bytes in the stream
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setBinaryStream(int, InputStream, int)
   */
  void setBinaryStream(String parameterName, java.io.InputStream x,
      int length) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @param targetSqlType the SQL type (as defined in java.sql.Types) to be sent to the database
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setObject(int, Object, int)
   */
  void setObject(String parameterName, Object x, int targetSqlType)
      throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setObject(int, Object)
   */
  void setObject(String parameterName, Object x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param reader        the java.io.Reader object that contains the Unicode data
   * @param length        the number of characters in the stream
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setCharacterStream(int, Reader, int)
   */
  void setCharacterStream(String parameterName,
      java.io.Reader reader,
      int length) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             an SQL REF value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setRef(int, Ref)
   */
  void setRef(String parameterName, Ref x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             a Blob object that maps an SQL BLOB value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setBlob(int, Blob) (int, Blob)
   */
  void setBlob(String parameterName, Blob x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             a Clob object that maps an SQL CLOB value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setClob(int, Clob)
   */
  void setClob(String parameterName, Clob x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             an Array object that maps an SQL ARRAY value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setArray(int, Array)
   */
  void setArray(String parameterName, Array x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @param cal           the Calendar object the driver will use to construct the date
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setDate(int, Date, Calendar)
   */
  void setDate(String parameterName, java.sql.Date x, Calendar cal) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @param cal           the Calendar object the driver will use to construct the date
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setTime(int, Time, Calendar)
   */
  void setTime(String parameterName, java.sql.Time x, Calendar cal) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @param cal           the Calendar object the driver will use to construct the date
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setTimestamp(int, Timestamp, Calendar)
   */
  void setTimestamp(String parameterName, java.sql.Timestamp x, Calendar cal) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param sqlType       a value from java.sql.Types
   * @param typeName      the fully-qualified name of an SQL user-defined type; ignored if the
   *                      parameter is not a user-defined type or REF
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setNull(int, int, String)
   */
  void setNull(String parameterName, int sqlType, String typeName) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setRowId(int, RowId)
   */
  void setRowId(String parameterName, RowId x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param value         the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setNString(int, String)
   */
  void setNString(String parameterName, String value) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param value         the parameter value
   * @param length        the number of characters in the parameter data
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setNCharacterStream(int, Reader, long)
   */
  void setNCharacterStream(String parameterName, Reader value, long length) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param value         the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setNClob(int, NClob)
   */
  void setNClob(String parameterName, NClob value) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param reader        An object that contains the data to set the parameter value to
   * @param length        the number of characters in the parameter data
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setClob(int, Reader, long)
   */
  void setClob(String parameterName, Reader reader, long length) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param inputStream   An object that contains the data to set the parameter value to
   * @param length        the number of bytes in the parameter data
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setBlob(int, InputStream, long)
   */
  void setBlob(String parameterName, InputStream inputStream, long length) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param reader        An object that contains the data to set the parameter value to
   * @param length        the number of characters in the parameter data
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setNClob(int, Reader, long)
   */
  void setNClob(String parameterName, Reader reader, long length) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param xmlObject     a SQLXML object that maps an SQL XML value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setSQLXML(int, SQLXML)
   */
  void setSQLXML(String parameterName, SQLXML xmlObject) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the object containing the input parameter value
   * @param targetSqlType the SQL type (as defined in java.sql.Types) to be sent to the database.
   *                      The scale argument may further qualify this type.
   * @param scaleOrLength for java.sql.Types.DECIMAL or java.sql.Types.NUMERIC types, this is the
   *                      number of digits after the decimal point. For Java Object types
   *                      InputStream and Reader, this is the length of the data in the stream or
   *                      reader. For all other types, this value will be ignored.
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setObject(int, Object, int, int)
   */
  void setObject(String parameterName, Object x, int targetSqlType, int scaleOrLength)
      throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the Java input stream that contains the ASCII parameter value
   * @param length        the number of bytes in the parameter data
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setAsciiStream(int, InputStream, long)
   */
  void setAsciiStream(String parameterName, java.io.InputStream x, long length)
      throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the Java input stream that contains the binary parameter value
   * @param length        the number of bytes in the parameter data
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setBinaryStream(int, InputStream, long)
   */
  void setBinaryStream(String parameterName, java.io.InputStream x,
      long length) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param reader        the java.io.Reader object that contains the Unicode data
   * @param length        the number of characters in the parameter data
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setCharacterStream(int, Reader, long)
   */
  void setCharacterStream(String parameterName,
      java.io.Reader reader,
      long length) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the Java input stream that contains the ASCII parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setAsciiStream(int, InputStream)
   */
  void setAsciiStream(String parameterName, java.io.InputStream x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             the Java input stream that contains the binary parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setBinaryStream(int, InputStream)
   */
  void setBinaryStream(String parameterName, java.io.InputStream x) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param reader        the java.io.Reader object that contains the Unicode data
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setCharacterStream(int, Reader)
   */
  void setCharacterStream(String parameterName,
      java.io.Reader reader) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param value         the parameter value
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setNCharacterStream(int, Reader)
   */
  void setNCharacterStream(String parameterName, Reader value) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param reader        An object that contains the data to set the parameter value to.
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setClob(int, Reader)
   */
  void setClob(String parameterName, Reader reader) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param inputStream   An object that contains the data to set the parameter value to.
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setBlob(int, InputStream)
   */
  void setBlob(String parameterName, InputStream inputStream) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param reader        An object that contains the data to set the parameter value to.
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setNClob(int, Reader)
   */
  void setNClob(String parameterName, Reader reader) throws SQLException;

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             An object that contains the data to set the parameter value to.
   * @param targetSqlType the SQL type to be sent to the database. The scale argument may further
   *                      qualify this type.
   * @param scaleOrLength for java.sql.JDBCType.DECIMAL or java.sql.JDBCType.NUMERIC types, this is
   *                      the number of digits after the decimal point. For Java Object types
   *                      InputStream and Reader, this is the length of the data in the stream or
   *                      reader. For all other types, this value will be ignored.
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setObject(int, Object, SQLType, int)
   */
  default void setObject(String parameterName, Object x, SQLType targetSqlType,
      int scaleOrLength) throws SQLException {
    throw new SQLFeatureNotSupportedException("setObject not implemented");
  }

  /**
   * @param parameterName the name of the parameter to be bound
   * @param x             An object that contains the data to set the parameter value to.
   * @param targetSqlType the SQL type to be sent to the database. The scale argument may further
   *                      qualify this type.
   * @throws SQLException if something goes wrong
   * @see java.sql.PreparedStatement#setObject(int, Object, SQLType)
   */
  default void setObject(String parameterName, Object x, SQLType targetSqlType)
      throws SQLException {
    throw new SQLFeatureNotSupportedException("setObject not implemented");
  }

  /**
   * @return returns true if a ParameterList exists, and it was created using a named placeholder strategy
   * @throws SQLException if something goes wrong
   */
  boolean hasParameterNames() throws SQLException;

  /**
   * @return a List of placeholder names, corresponding to the first occurrence of each placeholder
   * @throws SQLException if something goes wrong
   */
  List<String> getParameterNames() throws SQLException;
}
