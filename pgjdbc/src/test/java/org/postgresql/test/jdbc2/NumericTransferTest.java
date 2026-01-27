/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.PGProperty;
import org.postgresql.core.Oid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

@ParameterizedClass
@MethodSource("data")
public class NumericTransferTest extends BaseTest4 {
  public NumericTransferTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, Oid.NUMERIC);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Test
  public void receive100000() throws SQLException {
    Statement statement = con.createStatement();
    for (String sign : new String[]{"", "-"}) {
      for (int i = 0; i < 100; i++) {
        final String sql = "SELECT " + sign + "1E+" + i + "::numeric";
        ResultSet rs = statement.executeQuery(sql);
        rs.next();
        if (i == 0) {
          final String expected = sign + "1";
          assertEquals(expected, rs.getString(1), () -> "getString for " + sql);
          assertEquals(expected, rs.getBigDecimal(1).toString(), () -> "getBigDecimal for " + sql);
        } else {
          final String expected = sign + String.format("1%0" + i + "d", 0);
          assertEquals(expected, rs.getString(1), () -> "getString for " + sql);
          assertEquals(expected, rs.getBigDecimal(1).toString(), () -> "getBigDecimal for " + sql);
        }
        rs.close();
      }
    }
    statement.close();
  }

  @Test
  public void sendReceive100000() throws SQLException {
    PreparedStatement statement = con.prepareStatement("select ?::numeric");
    for (String sign : new String[]{"", "-"}) {
      for (int i = 0; i < 100; i++) {
        final String expected = sign + (i == 0 ? 1 : String.format("1%0" + i + "d", 0));
        statement.setBigDecimal(1, new BigDecimal(expected));
        ResultSet rs = statement.executeQuery();
        rs.next();
        assertEquals(
            expected,
            rs.getString(1),
            () -> "getString for " + expected);
        assertEquals(
            expected,
            rs.getBigDecimal(1).toString(),
            () -> "getBigDecimal for " + expected);
        rs.close();
      }
    }
    statement.close();
  }
}
