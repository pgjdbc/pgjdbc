/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.Oid;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

@RunWith(Parameterized.class)
public class NumericTransferTest2 extends BaseTest4 {

  final BigDecimal value;

  public NumericTransferTest2(BinaryMode binaryMode, BigDecimal value) {
    setBinaryMode(binaryMode);
    this.value = value;
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, Oid.NUMERIC);
  }

  @Parameterized.Parameters(name = "binary = {0}, value = {1,number,#,###.##################################################}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> numbers = new ArrayList<Object[]>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      numbers.add(new Object[] {binaryMode, new BigDecimal("1.0")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("0.000000000000000000000000000000000000000000000000000")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("0.100000000000000000000000000000000000000000000009900")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("-1.0")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("-1")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("1.2")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("-2.05")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("0.000000000000000000000000000990")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("-0.000000000000000000000000000990")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("10.0000000000099")});
      numbers.add(new Object[] {binaryMode, new BigDecimal(".10000000000000")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("1.10000000000000")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("99999.2")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("99999")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("-99999.2")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("-99999")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("2147483647")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("-2147483648")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("2147483648")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("-2147483649")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("9223372036854775807")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("-9223372036854775808")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("9223372036854775808")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("-9223372036854775809")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("10223372036850000000")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("19223372036854775807")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("19223372036854775807.300")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("-19223372036854775807.300")});
      numbers.add(new Object[] {binaryMode, new BigDecimal(BigInteger.valueOf(1234567890987654321L), -1)});
      numbers.add(new Object[] {binaryMode, new BigDecimal(BigInteger.valueOf(1234567890987654321L), -5)});
      numbers.add(new Object[] {binaryMode, new BigDecimal(BigInteger.valueOf(-1234567890987654321L), -3)});
      numbers.add(new Object[] {binaryMode, new BigDecimal(BigInteger.valueOf(6), -8)});
      numbers.add(new Object[] {binaryMode, new BigDecimal("30000")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("40000").setScale(15)});
      numbers.add(new Object[] {binaryMode, new BigDecimal("20000.00000000000000000000")});
      numbers.add(new Object[] {binaryMode, new BigDecimal("9990000").setScale(10)});
      numbers.add(new Object[] {binaryMode, new BigDecimal("1000000").setScale(20)});
      numbers.add(new Object[] {binaryMode, new BigDecimal("10000000000000000000000000000000000000").setScale(20)});
      numbers.add(new Object[] {binaryMode, new BigDecimal("90000000000000000000000000000000000000")});
    }
    return numbers;
  }

  @Test
  public void receiveValue() throws SQLException {
    final String valString = value.toPlainString();
    try (Statement statement = con.createStatement()) {
      final String sql = "SELECT " + valString + "::numeric";
      try (ResultSet rs = statement.executeQuery(sql)) {
        assertTrue(rs.next());
        assertEquals("getBigDecimal for " + sql, valString, rs.getBigDecimal(1).toPlainString());
      }
    }
  }

  @Test
  public void sendReceiveValue() throws SQLException {
    final String valString = value.toPlainString();
    try (PreparedStatement statement = con.prepareStatement("select ?::numeric")) {
      statement.setBigDecimal(1, value);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        assertEquals("getBigDecimal for " + valString, valString, rs.getBigDecimal(1).toPlainString());
      }
    }
  }
}
