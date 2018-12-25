/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Timestamp;

/**
 * A user-defined data type implemented as a domain over timestamptz on the
 * server-side.
 */
public class SaleDate implements SQLData {

  /**
   * The timestamp for the sale date.
   */
  private Timestamp timestamp;

  public SaleDate(Timestamp timestamp) {
    if (timestamp == null) {
      throw new IllegalArgumentException();
    }
    this.timestamp = timestamp;
  }

  @Override
  public boolean equals(Object O) {
    if (!(O instanceof SaleDate)) {
      return false;
    }
    SaleDate other = (SaleDate)O;
    return timestamp.equals(other.timestamp);
  }

  @Override
  public int hashCode() {
    return timestamp.hashCode();
  }

  @Override
  public String toString() {
    return timestamp.toString();
  }

  // <editor-fold defaultstate="collapsed" desc="SQLData">
  private String sqlTypeName;

  public SaleDate() {
  }

  @Override
  public String getSQLTypeName() {
    return sqlTypeName;
  }

  @Override
  public void writeSQL(SQLOutput stream) throws SQLException {
    stream.writeTimestamp(timestamp);
  }

  @Override
  public void readSQL(SQLInput stream, String typeName) throws SQLException {
    if (typeName == null) {
      throw new IllegalArgumentException();
    }
    sqlTypeName = typeName;
    if (timestamp != null) {
      throw new IllegalStateException();
    }
    timestamp = stream.readTimestamp();
    if (timestamp == null) {
      throw new SQLException("Null value unexpected");
    }
  }
}
