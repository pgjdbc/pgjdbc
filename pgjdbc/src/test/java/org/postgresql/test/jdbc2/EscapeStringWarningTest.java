/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.EnabledForServerVersionRange;
import org.postgresql.util.PGobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Fails when a parameter the driver writes into the SQL text leaves a warning on the statement, or
 * does not come back unchanged, on a server with {@code standard_conforming_strings} off.
 *
 * <p>{@link PreferQueryMode#SIMPLE} writes the parameter into the SQL text as a literal, and with
 * {@code standard_conforming_strings} off the driver doubles every backslash in it and writes an
 * escape string constant, {@code E'a\\b'}. A plain literal reads a doubled backslash the same way,
 * so the value arrives intact either way. A server with {@code escape_string_warning} on used to
 * send the warning {@code nonstandard use of \\ in a string literal} for each plain literal
 * carrying a backslash, and the driver passed each one to the caller as a
 * {@link java.sql.SQLWarning} on the statement.</p>
 *
 * <p>Each test binds one parameter shape: a backslash, which produces the warning, a quote, which
 * exercises the other half of the escaping, the two together, and neither of them. A last one binds
 * a {@code bytea} value as escape-format text, which reaches
 * {@code SimpleParameterList.quoteAndCast} through its {@code bytea} call site rather than the text
 * one.</p>
 *
 * <p>The class runs on PostgreSQL 18 and older. PostgreSQL 19 rejects
 * {@code standard_conforming_strings=off} and removed {@code escape_string_warning} along with it,
 * so there is nothing left to check there.</p>
 */
@ParameterizedClass
@MethodSource("data")
@EnabledForServerVersionRange(lt = "19")
public class EscapeStringWarningTest extends BaseTest4 {

  private final boolean standardConformingStrings;

  public EscapeStringWarningTest(PreferQueryMode preferQueryMode,
      boolean standardConformingStrings) {
    setPreferQueryMode(preferQueryMode);
    this.standardConformingStrings = standardConformingStrings;
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (PreferQueryMode preferQueryMode
        : new PreferQueryMode[]{PreferQueryMode.SIMPLE, PreferQueryMode.EXTENDED}) {
      for (boolean standardConformingStrings : new boolean[]{true, false}) {
        ids.add(new Object[]{preferQueryMode, standardConformingStrings});
      }
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SET standard_conforming_strings TO "
          + (standardConformingStrings ? "on" : "off"));
      // The warning under test is the one this setting turns on, and it is on by default
      stmt.execute("SET escape_string_warning TO on");
    }
    assertTrue(standardConformingStrings == TestUtil.getStandardConformingStrings(con),
        "the driver should have picked up the SET from the server's ParameterStatus message");
    TestUtil.createTempTable(con, "escapewarning", "value text");
    TestUtil.createTempTable(con, "escapewarningbytea", "value bytea");
  }

  @Test
  public void backslash() throws SQLException {
    checkParameter("a\\b");
  }

  @Test
  public void quote() throws SQLException {
    checkParameter("it's");
  }

  @Test
  public void backslashBeforeQuote() throws SQLException {
    checkParameter("a\\'b");
  }

  @Test
  public void neitherBackslashNorQuote() throws SQLException {
    checkParameter("plain");
  }

  /**
   * {@code \001} is how the {@code bytea} escape format spells the byte 1 and {@code \\} is how it
   * spells a backslash, so the bound value carries a backslash in both roles the format gives one.
   */
  @Test
  public void byteaAsEscapeFormatText() throws SQLException {
    PGobject bytea = new PGobject();
    bytea.setType("bytea");
    bytea.setValue("\\001\\\\\\002");
    try (PreparedStatement pstmt =
             con.prepareStatement("INSERT INTO escapewarningbytea VALUES (?)")) {
      pstmt.setObject(1, bytea);
      pstmt.executeUpdate();
      assertNull(pstmt.getWarnings(),
          "the server should accept the bytea literal the driver wrote without a warning");
    }
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT value FROM escapewarningbytea")) {
      assertTrue(rs.next(), "the insert should have left one row in escapewarningbytea");
      assertArrayEquals(new byte[]{0x01, 0x5c, 0x02}, rs.getBytes(1),
          "the bytea parameter should survive the round trip");
    }
  }

  private void checkParameter(String value) throws SQLException {
    try (PreparedStatement pstmt =
             con.prepareStatement("INSERT INTO escapewarning VALUES (?)")) {
      pstmt.setString(1, value);
      pstmt.executeUpdate();
      assertNull(pstmt.getWarnings(),
          "the server should accept the literal the driver wrote for " + value
              + " without a warning");
    }
    assertEquals(value, selectValue(), "the parameter should survive the round trip");
  }

  private String selectValue() throws SQLException {
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT value FROM escapewarning")) {
      assertTrue(rs.next(), "the insert should have left one row in escapewarning");
      return rs.getString(1);
    }
  }
}
