/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGobject;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Properties;

/**
 * Covers {@code refcursor}, a built-in type whose server {@code typsend} is {@code textsend}: its
 * binary wire is the charset text, so {@code TextLikeCodec} decodes it to a {@link PGobject} in
 * either wire format, including a field nested in a binary {@code record}. Before the codec, such a
 * field surfaced as {@code PGUnknownBinary}.
 */
public class RefcursorTextLikeBinaryTest {

  private static Properties binaryProps(int... oids) {
    Properties props = new Properties();
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    StringBuilder enable = new StringBuilder();
    for (int i = 0; i < oids.length; i++) {
      if (i > 0) {
        enable.append(',');
      }
      enable.append(oids[i]);
    }
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, enable.toString());
    return props;
  }

  /**
   * The motivating case: a {@code refcursor} field in a binary {@code record} decodes to a readable
   * {@code PGobject}, not {@code PGUnknownBinary}. A binary record yields one attribute; a text
   * record carries no field type and would yield none, so a non-empty attribute set also confirms
   * the field arrived in binary.
   */
  @Test
  void refcursorFieldInBinaryRecordDecodesToPGobject() throws SQLException {
    try (Connection con = TestUtil.openDB(binaryProps(Oid.RECORD, Oid.REFCURSOR))) {
      // The simple query protocol cannot negotiate binary format codes, so the record would come
      // back as text and, per the note above, expose no fields at all.
      assumeTrue(con.unwrap(PgConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE);
      try (PreparedStatement ps = con.prepareStatement("select row('mycur'::refcursor)");
          ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "one row expected");
        Struct struct = assertInstanceOf(Struct.class, rs.getObject(1));
        Object[] attributes = struct.getAttributes();
        assertEquals(1, attributes.length, "a binary record exposes its single field");
        PGobject field = assertInstanceOf(PGobject.class, attributes[0],
            "the text-send field decodes to a PGobject, not PGUnknownBinary");
        assertEquals("mycur", field.getValue());
      }
    }
  }

  /**
   * Top level over binary: with {@code refcursor} forced into the binary receive set, the value is
   * read from its binary wire (the charset text). {@code getString} is used rather than
   * {@code getObject}, which dereferences the named cursor (legacy behaviour, covered below).
   */
  @Test
  void refcursorReadOverBinaryYieldsTheName() throws SQLException {
    try (Connection con = TestUtil.openDB(binaryProps(Oid.REFCURSOR))) {
      assertTrue(con.unwrap(BaseConnection.class).getQueryExecutor().useBinaryForReceive(Oid.REFCURSOR),
          "refcursor must be received in binary for this to exercise the binary path");
      try (PreparedStatement ps = con.prepareStatement("select 'mycur'::refcursor");
           ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "one row expected");
        assertEquals("mycur", rs.getString(1));
      }
    }
  }

  /**
   * {@code getObject(col, PGobject.class)} over binary must keep the value. The bare-PGobject path
   * in {@code PgResultSet} used to hand binary bytes to a generic {@code PGobject}, which has no
   * binary parser, leaving a typed but empty value; it now decodes the wire to text via the codec.
   */
  @Test
  void getObjectAsPGobjectOverBinaryKeepsValue() throws SQLException {
    try (Connection con = TestUtil.openDB(binaryProps(Oid.REFCURSOR))) {
      assertTrue(con.unwrap(BaseConnection.class).getQueryExecutor().useBinaryForReceive(Oid.REFCURSOR),
          "refcursor must be received in binary");
      try (PreparedStatement ps = con.prepareStatement("select 'mycur'::refcursor");
           ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "one row expected");
        PGobject obj = rs.getObject(1, PGobject.class);
        assertEquals("refcursor", obj.getType());
        assertEquals("mycur", obj.getValue());
      }
    }
  }

  /**
   * Regression guard: making {@code refcursor} binary-eligible must not break the legacy
   * {@code getObject} cursor dereference, which opens the named cursor and returns its rows.
   */
  @Test
  void cursorDereferenceStillWorksOverBinary() throws SQLException {
    try (Connection con = TestUtil.openDB(binaryProps(Oid.REFCURSOR))) {
      con.setAutoCommit(false);
      try (Statement st = con.createStatement()) {
        st.execute("DECLARE mycur CURSOR FOR SELECT 42 AS n");
      }
      try (PreparedStatement ps = con.prepareStatement("select 'mycur'::refcursor");
           ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "one row expected");
        ResultSet cursor = assertInstanceOf(ResultSet.class, rs.getObject(1),
            "getObject on a refcursor column dereferences the cursor");
        assertTrue(cursor.next(), "the dereferenced cursor yields its row");
        assertEquals(42, cursor.getInt(1));
      } finally {
        con.rollback();
      }
    }
  }
}
