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
  private final String pgType;

  PgPreparedStatementSQLOutput(PgPreparedStatement pstmt, int parameterIndex, String pgType) {
    this.pstmt = pstmt;
    this.parameterIndex = parameterIndex;
    this.pgType = pgType;
  }

  @Override
  public void writeString(String x) throws SQLException {
    markWrite();
    pstmt.setStringImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeBoolean(boolean x) throws SQLException {
    markWrite();
    pstmt.setBooleanImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeByte(byte x) throws SQLException {
    markWrite();
    pstmt.setByteImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeShort(short x) throws SQLException {
    markWrite();
    pstmt.setShortImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeInt(int x) throws SQLException {
    markWrite();
    pstmt.setIntImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeLong(long x) throws SQLException {
    markWrite();
    pstmt.setLongImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeFloat(float x) throws SQLException {
    markWrite();
    pstmt.setFloatImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeDouble(double x) throws SQLException {
    markWrite();
    pstmt.setDoubleImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeBigDecimal(BigDecimal x) throws SQLException {
    markWrite();
    pstmt.setBigDecimalImpl(parameterIndex, pgType, -1, x);
  }

  @Override
  public void writeBytes(byte[] x) throws SQLException {
    markWrite();
    pstmt.setBytesImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeDate(Date x) throws SQLException {
    markWrite();
    pstmt.setDateImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeTime(Time x) throws SQLException {
    markWrite();
    pstmt.setTimeImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeTimestamp(Timestamp x) throws SQLException {
    markWrite();
    pstmt.setTimestampImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeCharacterStream(Reader x) throws SQLException {
    markWrite();
    pstmt.setCharacterStreamImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeAsciiStream(InputStream x) throws SQLException {
    markWrite();
    pstmt.setAsciiStreamImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeBinaryStream(InputStream x) throws SQLException {
    markWrite();
    pstmt.setBinaryStreamImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeObject(SQLData x) throws SQLException {
    if (x == null) {
      markWrite();
      pstmt.setNull(parameterIndex, Types.OTHER, pgType);
    } else {
      x.writeSQL(this);
      //PgSQLOutputHelper.writeSQLData(x, new PgPreparedStatementSQLOutput(pstmt, parameterIndex, pgType));
    }
    //pstmt.setObject(parameterIndex, pgType, x);
  }

  @Override
  public void writeRef(Ref x) throws SQLException {
    markWrite();
    pstmt.setRefImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeBlob(Blob x) throws SQLException {
    markWrite();
    pstmt.setBlobImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeClob(Clob x) throws SQLException {
    markWrite();
    pstmt.setClobImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeStruct(Struct x) throws SQLException {
    markWrite();
    pstmt.setObjectImpl(parameterIndex, pgType, -1, x);
  }

  @Override
  public void writeArray(Array x) throws SQLException {
    markWrite();
    pstmt.setArrayImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeURL(URL x) throws SQLException {
    markWrite();
    pstmt.setURLImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeNString(String x) throws SQLException {
    markWrite();
    pstmt.setNStringImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeNClob(NClob x) throws SQLException {
    markWrite();
    pstmt.setNClobImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeRowId(RowId x) throws SQLException {
    markWrite();
    pstmt.setRowIdImpl(parameterIndex, pgType, x);
  }

  @Override
  public void writeSQLXML(SQLXML x) throws SQLException {
    markWrite();
    pstmt.setSQLXMLImpl(parameterIndex, pgType, x);
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  @Override
  public void writeObject(Object x, SQLType targetSqlType) throws SQLException {
    markWrite();
    pstmt.setObjectImpl(parameterIndex, pgType, x, targetSqlType);
  }
  //#endif
}
