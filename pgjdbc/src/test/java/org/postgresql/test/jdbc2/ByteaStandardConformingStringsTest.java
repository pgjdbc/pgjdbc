/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.ByteBufferByteStreamWriter;
import org.postgresql.util.PGobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;

/**
 * A {@code bytea} parameter comes back unchanged and draws no warning on a server with
 * {@code standard_conforming_strings} off.
 *
 * <p>In {@link PreferQueryMode#SIMPLE} the driver writes the parameter into the SQL text as a hex
 * literal, and the hex format opens with a backslash. A server with
 * {@code standard_conforming_strings} off reads a backslash in a plain literal as an escape
 * character, so the driver writes the literal as an escape string constant,
 * {@code E'\\xcafebabe'::bytea}. A plain literal with the backslash doubled parses to the same
 * bytes, but such a server reports {@code WARNING: nonstandard use of \\ in a string literal} for
 * it while {@code escape_string_warning} is on, and the driver reports one {@code SQLWarning} to
 * the caller for every such parameter.</p>
 *
 * <p>The driver used to write a plain literal, and such a server read the backslash as an escape.
 * For {@link #DATA} that made {@code \x00} one escape, a NUL that no encoding accepts, and the
 * server refused the statement with {@code invalid byte sequence for encoding "UTF8": 0x00}. For an
 * empty value there were no hex digits to consume, so the server dropped the backslash and stored
 * the {@code x} left behind as the byte {@code 0x78}.</p>
 *
 * <p>There is one test per input the driver formats on its own path, plus one for the empty value
 * and one for the warning. Only {@link PreferQueryMode#SIMPLE} with the setting off reaches an
 * escape string constant. The
 * controls are {@link PreferQueryMode#SIMPLE} with the setting on, and
 * {@link PreferQueryMode#EXTENDED} with either setting.</p>
 */
@ParameterizedClass
@MethodSource("data")
public class ByteaStandardConformingStringsTest extends BaseTest4 {

  /**
   * The non-empty value the tests bind. The leading NUL used to reach the server as an escape and
   * fail the statement, so keep it first. The rest carries {@code 0xe6} and {@code 0xff} from above
   * the ASCII range, two bytes whose own ASCII form is a hex digit ({@code 0x39} and {@code 0x37}),
   * and the backslash and quote, which the hex encoding renders as ordinary digit pairs like every
   * other byte.
   */
  private static final byte[] DATA = {0x00, 0x01, (byte) 0xe6, 0x39, 0x37, (byte) 0xff, '\\', '\''};

  private final boolean standardConformingStrings;

  public ByteaStandardConformingStringsTest(PreferQueryMode preferQueryMode,
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
    assumeTrue(standardConformingStrings
            || !TestUtil.haveMinimumServerVersion(con, ServerVersion.v19),
        "PostgreSQL 19 accepts standard_conforming_strings=on only");
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SET standard_conforming_strings TO "
          + (standardConformingStrings ? "on" : "off"));
      if (!standardConformingStrings) {
        // Without this, insertingAByteArrayDrawsNoWarning would pass against a plain literal too.
        // Only the off half needs it: with the setting on a backslash starts no escape, so no
        // warning can arise, and PostgreSQL 19 has removed the parameter along with the setting
        stmt.execute("SET escape_string_warning TO on");
      }
    }
    // The server sends a ParameterStatus message for the new setting, and the driver records it
    assertEquals(standardConformingStrings, TestUtil.getStandardConformingStrings(con),
        "PgConnection.getStandardConformingStrings() after SET standard_conforming_strings");
    TestUtil.createTempTable(con, "byteatest", "data bytea");
  }

  @Test
  public void aByteArrayComesBackUnchanged() throws SQLException {
    try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO byteatest VALUES (?)")) {
      pstmt.setBytes(1, DATA);
      pstmt.executeUpdate();
    }
    assertArrayEquals(DATA, selectData());
  }

  @Test
  public void anEmptyByteArrayComesBackEmpty() throws SQLException {
    try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO byteatest VALUES (?)")) {
      pstmt.setBytes(1, new byte[0]);
      pstmt.executeUpdate();
    }
    assertArrayEquals(new byte[0], selectData());
  }

  @Test
  public void aBinaryStreamComesBackUnchanged() throws SQLException {
    try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO byteatest VALUES (?)")) {
      pstmt.setBinaryStream(1, new ByteArrayInputStream(DATA), DATA.length);
      pstmt.executeUpdate();
    }
    assertArrayEquals(DATA, selectData());
  }

  @Test
  public void aByteStreamWriterComesBackUnchanged() throws SQLException {
    try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO byteatest VALUES (?)")) {
      pstmt.setObject(1, new ByteBufferByteStreamWriter(ByteBuffer.wrap(DATA)));
      pstmt.executeUpdate();
    }
    assertArrayEquals(DATA, selectData());
  }

  @Test
  public void aHexFormatPGobjectComesBackUnchanged() throws SQLException {
    PGobject bytea = new PGobject();
    bytea.setType("bytea");
    bytea.setValue(toHexFormat(DATA));
    try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO byteatest VALUES (?)")) {
      pstmt.setObject(1, bytea);
      pstmt.executeUpdate();
    }
    assertArrayEquals(DATA, selectData());
  }

  @Test
  public void insertingAByteArrayDrawsNoWarning() throws SQLException {
    try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO byteatest VALUES (?)")) {
      pstmt.setBytes(1, DATA);
      pstmt.executeUpdate();
      assertNull(pstmt.getWarnings(), "PreparedStatement.getWarnings() after the insert");
    }
  }

  private byte[] selectData() throws SQLException {
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT data FROM byteatest")) {
      assertTrue(rs.next(), "the insert should have left one row in byteatest");
      return rs.getBytes(1);
    }
  }

  private static String toHexFormat(byte[] data) {
    StringBuilder sb = new StringBuilder(2 + 2 * data.length);
    sb.append("\\x");
    for (byte b : data) {
      sb.append(Character.forDigit((b >> 4) & 0xf, 16));
      sb.append(Character.forDigit(b & 0xf, 16));
    }
    return sb.toString();
  }
}
