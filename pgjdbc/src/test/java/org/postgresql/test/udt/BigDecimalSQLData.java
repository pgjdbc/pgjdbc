/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import java.math.BigDecimal;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;

/**
 * Extends BigDecimal and is SQLData.  Used to test that custom types take precedence
 * over default type mappings.
 */
public class BigDecimalSQLData extends BigDecimal implements SQLData {

  public static final BigDecimal CONSTANT_VALUE = new BigDecimal("1345.4325");

  public BigDecimalSQLData(String val) {
    super(val);
  }

  // <editor-fold defaultstate="collapsed" desc="SQLData">
  private String sqlTypeName;

  public BigDecimalSQLData() {
    // This value is not important - we're just testing that the correct types of objects are created
    super(CONSTANT_VALUE.toString());
  }

  @Override
  public String getSQLTypeName() {
    return sqlTypeName;
  }

  @Override
  public void writeSQL(SQLOutput stream) throws SQLException {
    stream.writeBigDecimal(CONSTANT_VALUE);
  }

  @Override
  public void readSQL(SQLInput stream, String typeName) throws SQLException {
    if (typeName == null) {
      throw new IllegalArgumentException();
    }
    sqlTypeName = typeName;
    BigDecimal newValue = stream.readBigDecimal();
    // BigDecimal is ummutable, and we can't replace the value - just testing types returned
    if (newValue == null) {
      throw new SQLException("Null value unexpected");
    }
  }
}
