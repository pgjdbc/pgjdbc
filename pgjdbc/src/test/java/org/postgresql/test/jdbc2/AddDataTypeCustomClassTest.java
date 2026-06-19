/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGConnection;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGobject;
import org.postgresql.util.PGtokenizer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Verifies that a custom {@link PGobject} subclass registered through
 * {@link PGConnection#addDataType(String, Class)} is returned by plain
 * {@code getObject}, independent of the registered identifier form and of any
 * preceding {@code PreparedStatement} insert.
 *
 * <p>Regression test for
 * <a href="https://github.com/pgjdbc/pgjdbc/issues/3562">issue #3562</a>, where
 * a {@code PreparedStatement} insert corrupted the type cache so the following
 * {@code SELECT} returned a plain {@code String}/{@code PgStruct} instead of the
 * registered class. The {@code bp_status} type there is a table row type, used as
 * the type of the {@code status} column.</p>
 */
class AddDataTypeCustomClassTest {

  /** Mirrors the reporter's CodeList: a PGobject populated from composite text. */
  public static class CodeList extends PGobject {
    public String codespace;
    public String id;
    public String codelistValue;

    @Override
    public void setValue(String value) throws SQLException {
      super.setValue(value);
      String s = PGtokenizer.removePara(PGtokenizer.removeCurlyBrace(value));
      PGtokenizer t = new PGtokenizer(s, ',');
      codespace = t.getToken(0);
      id = t.getToken(1);
      codelistValue = t.getSize() > 2 ? t.getToken(2) : null;
    }
  }

  /** A PGobject for a scalar (enum) value. */
  public static class StatusCode extends PGobject {
  }

  private Connection con;

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createSchema(con, "xplan_gml");
    // bp_status is a TABLE; its row type is used as the column type (the issue's setup).
    TestUtil.createTable(con, "xplan_gml.bp_status",
        "codespace text, id varchar NOT NULL, value text, "
            + "CONSTRAINT bp_status_pkey PRIMARY KEY (id)");
    TestUtil.createEnumType(con, "xplan_gml.bp_kind", "'a', 'b', 'c'");
    TestUtil.createTable(con, "xplan_gml.jdbctest",
        "gml_id uuid, status xplan_gml.bp_status, kind xplan_gml.bp_kind, "
            + "kinds xplan_gml.bp_kind[]");
  }

  @AfterEach
  void tearDown() throws Exception {
    if (con != null) {
      TestUtil.dropTable(con, "xplan_gml.jdbctest");
      TestUtil.dropTable(con, "xplan_gml.bp_status");
      TestUtil.dropType(con, "xplan_gml.bp_kind");
      TestUtil.dropSchema(con, "xplan_gml");
      TestUtil.closeDB(con);
    }
  }

  private Object readColumn(UUID id, String column) throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(
        "SELECT " + column + " FROM xplan_gml.jdbctest WHERE gml_id = ?")) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "row must be present");
        return rs.getObject(1);
      }
    }
  }

  @Test
  void compositeReturnsRegisteredClassWithoutPreparedStatementInsert() throws Exception {
    con.unwrap(PGConnection.class)
        .addDataType("\"xplan_gml\".\"bp_status\"", CodeList.class);
    UUID id = UUID.randomUUID();
    TestUtil.execute(con, "INSERT INTO xplan_gml.jdbctest(gml_id, status) VALUES ('"
        + id + "', ROW('cs','4000','draft')::xplan_gml.bp_status)");

    CodeList status = assertInstanceOf(CodeList.class, readColumn(id, "status"));
    assertEquals("cs", status.codespace);
    assertEquals("4000", status.id);
  }

  @Test
  void compositeReturnsRegisteredClassAfterPreparedStatementInsert() throws Exception {
    // The issue #3562 scenario: the PreparedStatement insert must not change the
    // type of the value the subsequent SELECT returns.
    con.unwrap(PGConnection.class)
        .addDataType("\"xplan_gml\".\"bp_status\"", CodeList.class);
    UUID id = UUID.randomUUID();
    CodeList toInsert = new CodeList();
    toInsert.setType("\"xplan_gml\".\"bp_status\"");
    toInsert.setValue("(cs,4000,draft)");
    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO xplan_gml.jdbctest(gml_id, status) VALUES (?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, toInsert);
      ps.execute();
    }

    CodeList status = assertInstanceOf(CodeList.class, readColumn(id, "status"));
    assertEquals("cs", status.codespace);
    assertEquals("4000", status.id);
  }

  @Test
  void registrationFormIsNormalizedByOid() throws Exception {
    // The type's display form is "\"xplan_gml\".\"bp_status\"" (off search_path),
    // but registering by the bare type name must still resolve via OID.
    con.unwrap(PGConnection.class).addDataType("bp_status", CodeList.class);
    UUID id = UUID.randomUUID();
    TestUtil.execute(con, "INSERT INTO xplan_gml.jdbctest(gml_id, status) VALUES ('"
        + id + "', ROW('cs','4000','draft')::xplan_gml.bp_status)");

    assertInstanceOf(CodeList.class, readColumn(id, "status"));
  }

  @Test
  void enumReturnsRegisteredClass() throws Exception {
    con.unwrap(PGConnection.class)
        .addDataType("\"xplan_gml\".\"bp_kind\"", StatusCode.class);
    UUID id = UUID.randomUUID();
    TestUtil.execute(con,
        "INSERT INTO xplan_gml.jdbctest(gml_id, kind) VALUES ('" + id + "', 'b')");

    StatusCode kind = assertInstanceOf(StatusCode.class, readColumn(id, "kind"));
    assertEquals("b", kind.getValue());
  }

  @Test
  void arrayElementsReturnRegisteredClass() throws Exception {
    // Registering by OID makes the mapping apply to array elements too, which a
    // top-level-only resolution would miss.
    con.unwrap(PGConnection.class)
        .addDataType("\"xplan_gml\".\"bp_kind\"", StatusCode.class);
    UUID id = UUID.randomUUID();
    TestUtil.execute(con, "INSERT INTO xplan_gml.jdbctest(gml_id, kinds) VALUES ('"
        + id + "', ARRAY['a','c']::xplan_gml.bp_kind[])");

    Array array = assertInstanceOf(Array.class, readColumn(id, "kinds"));
    Object[] elements = (Object[]) array.getArray();
    assertEquals(2, elements.length);
    assertInstanceOf(StatusCode.class, elements[0]);
    assertInstanceOf(StatusCode.class, elements[1]);
    assertEquals("a", ((StatusCode) elements[0]).getValue());
    assertEquals("c", ((StatusCode) elements[1]).getValue());
  }
}
