/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Reading {@code money} must be independent of the wire transfer mode. {@code money} is a 64-bit
 * count of the currency's minor unit, rendered by the server per {@code lc_monetary}; the driver keeps
 * it on the text receive format (it cannot rebuild that rendering from the raw int64), so the getters
 * behave the same whether {@code binaryTransfer} is on or off.
 *
 * <p>Each case reads {@code '<literal>'::money} and compares against expectations derived from the
 * literal, so the numeric assertions do not depend on the server's locale. {@code getString} is
 * compared against the server's own {@code ::text} rendering of the same value, which is
 * locale-dependent, so that check stays a same-row differential.</p>
 */
class MoneyGetterTest {
  /** Money literals with an exact two-digit scale: zero, the minor unit, both signs, the int64 ends. */
  private static final String[] LITERALS = {
      "0.00", "0.01", "1.00", "-1.00", "92233720368547758.07", "-92233720368547758.08",
  };

  private Connection textConn;
  private Connection binaryConn;

  @BeforeEach
  void setUp() throws Exception {
    Properties textProps = new Properties();
    PGProperty.BINARY_TRANSFER.set(textProps, "false");
    textConn = TestUtil.openDB(textProps);

    Properties binaryProps = new Properties();
    PGProperty.BINARY_TRANSFER.set(binaryProps, "true");
    // Force a server-prepared statement so binary transfer is actually used for the eligible types.
    PGProperty.PREPARE_THRESHOLD.set(binaryProps, "-1");
    binaryConn = TestUtil.openDB(binaryProps);
  }

  @AfterEach
  void tearDown() throws Exception {
    TestUtil.closeDB(textConn);
    TestUtil.closeDB(binaryConn);
  }

  @Test
  void moneyGettersUnderTextTransfer() throws SQLException {
    for (String literal : LITERALS) {
      checkMoney(textConn, "text", literal);
    }
  }

  @Test
  void moneyGettersUnderBinaryTransfer() throws SQLException {
    for (String literal : LITERALS) {
      checkMoney(binaryConn, "binary", literal);
    }
  }

  private static void checkMoney(Connection conn, String mode, String literal) throws SQLException {
    BigDecimal exact = new BigDecimal(literal); // scale 2, the value the server stores
    long wholeUnits = exact.toBigInteger().longValue();
    double asDouble = exact.doubleValue();
    String prefix = mode + " / " + literal + ": ";

    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT ('" + literal + "'::money)::text, '" + literal + "'::money");
         ResultSet rs = ps.executeQuery()) {
      rs.next();

      // getString hands back the server's locale rendering unchanged, whatever the transfer mode.
      assertEquals(rs.getString(1), rs.getString(2), prefix + "getString");

      Object object = rs.getObject(2);
      // getObject must return Double (money maps to Types.DOUBLE), matching the legacy contract.
      Double asObject = assertInstanceOf(Double.class, object, prefix + "getObject type");
      assertEquals(asDouble, asObject, prefix + "getObject value");
      assertEquals(asDouble, rs.getDouble(2), prefix + "getDouble");
      assertEquals(wholeUnits, rs.getLong(2), prefix + "getLong");
      assertEquals(0, rs.getBigDecimal(2).compareTo(exact), prefix + "getBigDecimal");
    }
  }
}
