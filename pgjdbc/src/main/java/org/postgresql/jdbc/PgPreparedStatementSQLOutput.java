/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.udt.SingleAttributeSQLOutput;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLOutput;
//#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
import java.sql.SQLType;
//#endif
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;


/**
 * Implementation of {@link SQLOutput} supporting a single write that sets the
 * parameter of a {@link PreparedStatement} at the given index.
 */
class PgPreparedStatementSQLOutput extends SingleAttributeSQLOutput {

  private final PgPreparedStatement pstmt;

  private final int parameterIndex;

  PgPreparedStatementSQLOutput(PgPreparedStatement pstmt, int parameterIndex) {
    this.pstmt = pstmt;
    this.parameterIndex = parameterIndex;
  }

  @Override
  public void writeString(String x) throws SQLException {
    markWrite();
    pstmt.setString(parameterIndex, x);
  }

  @Override
  public void writeBoolean(boolean x) throws SQLException {
    markWrite();
    pstmt.setBoolean(parameterIndex, x);
  }

  @Override
  public void writeByte(byte x) throws SQLException {
    markWrite();
    pstmt.setByte(parameterIndex, x);
  }

  @Override
  public void writeShort(short x) throws SQLException {
    markWrite();
    pstmt.setShort(parameterIndex, x);
  }

  @Override
  public void writeInt(int x) throws SQLException {
    markWrite();
    pstmt.setInt(parameterIndex, x);
  }

  @Override
  public void writeLong(long x) throws SQLException {
    markWrite();
    pstmt.setLong(parameterIndex, x);
  }

  @Override
  public void writeFloat(float x) throws SQLException {
    markWrite();
    pstmt.setFloat(parameterIndex, x);
  }

  @Override
  public void writeDouble(double x) throws SQLException {
    markWrite();
    pstmt.setDouble(parameterIndex, x);
  }

  @Override
  public void writeBigDecimal(BigDecimal x) throws SQLException {
    markWrite();
    pstmt.setBigDecimal(parameterIndex, x);
  }

  @Override
  public void writeBytes(byte[] x) throws SQLException {
    markWrite();
    pstmt.setBytes(parameterIndex, x);
  }

  @Override
  public void writeDate(Date x) throws SQLException {
    markWrite();
    pstmt.setDate(parameterIndex, x);
  }

  @Override
  public void writeTime(Time x) throws SQLException {
    markWrite();
    pstmt.setTime(parameterIndex, x);
  }

  @Override
  public void writeTimestamp(Timestamp x) throws SQLException {
    markWrite();
    pstmt.setTimestamp(parameterIndex, x);
  }

  @Override
  public void writeCharacterStream(Reader x) throws SQLException {
    markWrite();
    pstmt.setCharacterStream(parameterIndex, x);
  }

  @Override
  public void writeAsciiStream(InputStream x) throws SQLException {
    markWrite();
    pstmt.setAsciiStream(parameterIndex, x);
  }

  @Override
  public void writeBinaryStream(InputStream x) throws SQLException {
    markWrite();
    pstmt.setBinaryStream(parameterIndex, x);
  }

  @Override
  public void writeObject(SQLData x) throws SQLException {
    if (x == null) {
      markWrite();
      pstmt.setNull(parameterIndex, Types.OTHER);
    } else {
      x.writeSQL(this);
      //PgSQLOutputHelper.writeSQLData(x, new PgPreparedStatementSQLOutput(pstmt, parameterIndex));
    }
    //pstmt.setObject(parameterIndex, x);
  }

  @Override
  public void writeRef(Ref x) throws SQLException {
    markWrite();
    pstmt.setRef(parameterIndex, x);
  }

  @Override
  public void writeBlob(Blob x) throws SQLException {
    markWrite();
    pstmt.setBlob(parameterIndex, x);
  }

  @Override
  public void writeClob(Clob x) throws SQLException {
    markWrite();
    pstmt.setClob(parameterIndex, x);
  }

  @Override
  public void writeStruct(Struct x) throws SQLException {
    markWrite();
    pstmt.setObject(parameterIndex, x);
  }

  @Override
  public void writeArray(Array x) throws SQLException {
    markWrite();
    pstmt.setArray(parameterIndex, x);
  }

  @Override
  public void writeURL(URL x) throws SQLException {
    markWrite();
    pstmt.setURL(parameterIndex, x);
  }

  @Override
  public void writeNString(String x) throws SQLException {
    markWrite();
    pstmt.setNString(parameterIndex, x);
  }

  @Override
  public void writeNClob(NClob x) throws SQLException {
    markWrite();
    pstmt.setNClob(parameterIndex, x);
  }

  @Override
  public void writeRowId(RowId x) throws SQLException {
    markWrite();
    pstmt.setRowId(parameterIndex, x);
  }

  @Override
  public void writeSQLXML(SQLXML x) throws SQLException {
    markWrite();
    pstmt.setSQLXML(parameterIndex, x);
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  @Override
  public void writeObject(Object x, SQLType targetSqlType) throws SQLException {
    markWrite();
    pstmt.setObject(parameterIndex, x, targetSqlType);
  }
  //#endif
}
