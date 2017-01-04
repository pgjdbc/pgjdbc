/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/*
 * TestCase to test the internal functionality of org.postgresql.jdbc2.DatabaseMetaData
 *
 */
public class DatabaseMetaDataTest {
  private Connection con;

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createTable(con, "metadatatest",
        "id int4, name text, updated timestamptz, colour text, quest text");
    TestUtil.dropSequence(con, "sercoltest_b_seq");
    TestUtil.dropSequence(con, "sercoltest_c_seq");
    TestUtil.createTable(con, "sercoltest", "a int, b serial, c bigserial");
    TestUtil.createTable(con, "\"a\\\"", "a int4");
    TestUtil.createTable(con, "\"a'\"", "a int4");
    TestUtil.createTable(con, "arraytable", "a numeric(5,2)[], b varchar(100)[]");
    TestUtil.createTable(con, "intarraytable", "a int4[], b int4[][]");
    TestUtil.createCompositeType(con, "custom", "i int");
    TestUtil.createCompositeType(con, "_custom", "f float");

    // 8.2 does not support arrays of composite types
    TestUtil.createTable(con, "customtable", "c1 custom, c2 _custom"
        + (TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3) ? ", c3 custom[], c4 _custom[]" : ""));

    Statement stmt = con.createStatement();
    // we add the following comments to ensure the joins to the comments
    // are done correctly. This ensures we correctly test that case.
    stmt.execute("comment on table metadatatest is 'this is a table comment'");
    stmt.execute("comment on column metadatatest.id is 'this is a column comment'");

    stmt.execute(
        "CREATE OR REPLACE FUNCTION f1(int, varchar) RETURNS int AS 'SELECT 1;' LANGUAGE SQL");
    stmt.execute(
        "CREATE OR REPLACE FUNCTION f2(a int, b varchar) RETURNS int AS 'SELECT 1;' LANGUAGE SQL");
    stmt.execute(
        "CREATE OR REPLACE FUNCTION f3(IN a int, INOUT b varchar, OUT c timestamptz) AS $f$ BEGIN b := 'a'; c := now(); return; END; $f$ LANGUAGE plpgsql");
    stmt.execute(
        "CREATE OR REPLACE FUNCTION f4(int) RETURNS metadatatest AS 'SELECT 1, ''a''::text, now(), ''c''::text, ''q''::text' LANGUAGE SQL");
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4)) {
      // RETURNS TABLE requires PostgreSQL 8.4+
      stmt.execute(
          "CREATE OR REPLACE FUNCTION f5() RETURNS TABLE (i int) LANGUAGE sql AS 'SELECT 1'");
    }

    TestUtil.createDomain(con, "nndom", "int not null");
    TestUtil.createTable(con, "domaintable", "id nndom");
    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    // Drop function first because it depends on the
    // metadatatest table's type
    Statement stmt = con.createStatement();
    stmt.execute("DROP FUNCTION f4(int)");

    TestUtil.dropTable(con, "metadatatest");
    TestUtil.dropTable(con, "sercoltest");
    TestUtil.dropSequence(con, "sercoltest_b_seq");
    TestUtil.dropSequence(con, "sercoltest_c_seq");
    TestUtil.dropTable(con, "\"a\\\"");
    TestUtil.dropTable(con, "\"a'\"");
    TestUtil.dropTable(con, "arraytable");
    TestUtil.dropTable(con, "intarraytable");
    TestUtil.dropTable(con, "customtable");
    TestUtil.dropType(con, "custom");
    TestUtil.dropType(con, "_custom");

    stmt.execute("DROP FUNCTION f1(int, varchar)");
    stmt.execute("DROP FUNCTION f2(int, varchar)");
    stmt.execute("DROP FUNCTION f3(int, varchar)");
    TestUtil.dropType(con, "domaintable");
    TestUtil.dropDomain(con, "nndom");

    TestUtil.closeDB(con);
  }

  @Test
  public void testArrayTypeInfo() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns(null, null, "intarraytable", "a");
    assertTrue(rs.next());
    assertEquals("_int4", rs.getString("TYPE_NAME"));
    con.createArrayOf("integer", new Integer[] {});
    TestUtil.closeQuietly(rs);
    rs = dbmd.getColumns(null, null, "intarraytable", "a");
    assertTrue(rs.next());
    assertEquals("_int4", rs.getString("TYPE_NAME"));
    TestUtil.closeQuietly(rs);
  }

  @Test
  public void testArrayInt4DoubleDim() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns(null, null, "intarraytable", "b");
    assertTrue(rs.next());
    assertEquals("_int4", rs.getString("TYPE_NAME")); // even int4[][] is represented as _int4
    con.createArrayOf("int4", new int[][]{{1, 2}, {3, 4}});
    rs = dbmd.getColumns(null, null, "intarraytable", "b");
    assertTrue(rs.next());
    assertEquals("_int4", rs.getString("TYPE_NAME")); // even int4[][] is represented as _int4
  }

  @Test
  public void testCustomArrayTypeInfo() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet res = dbmd.getColumns(null, null, "customtable", null);
    assertTrue(res.next());
    assertEquals("custom", res.getString("TYPE_NAME"));
    assertTrue(res.next());
    assertEquals("_custom", res.getString("TYPE_NAME"));
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3)) {
      assertTrue(res.next());
      assertEquals("__custom", res.getString("TYPE_NAME"));
      assertTrue(res.next());
      assertEquals("___custom", res.getString("TYPE_NAME"));
    }
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3)) {
      con.createArrayOf("custom", new Object[]{});
      res = dbmd.getColumns(null, null, "customtable", null);
      assertTrue(res.next());
      assertEquals("custom", res.getString("TYPE_NAME"));
      assertTrue(res.next());
      assertEquals("_custom", res.getString("TYPE_NAME"));
      assertTrue(res.next());
      assertEquals("__custom", res.getString("TYPE_NAME"));
      assertTrue(res.next());
      assertEquals("___custom", res.getString("TYPE_NAME"));
    }
  }

  @Test
  public void testTables() throws Exception {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    ResultSet rs = dbmd.getTables(null, null, "metadatates%", new String[]{"TABLE"});
    assertTrue(rs.next());
    String tableName = rs.getString("TABLE_NAME");
    assertEquals("metadatatest", tableName);
    String tableType = rs.getString("TABLE_TYPE");
    assertEquals("TABLE", tableType);
    // There should only be one row returned
    assertTrue("getTables() returned too many rows", rs.next() == false);
    rs.close();

    rs = dbmd.getColumns("", "", "meta%", "%");
    assertTrue(rs.next());
    assertEquals("metadatatest", rs.getString("TABLE_NAME"));
    assertEquals("id", rs.getString("COLUMN_NAME"));
    assertEquals(java.sql.Types.INTEGER, rs.getInt("DATA_TYPE"));

    assertTrue(rs.next());
    assertEquals("metadatatest", rs.getString("TABLE_NAME"));
    assertEquals("name", rs.getString("COLUMN_NAME"));
    assertEquals(java.sql.Types.VARCHAR, rs.getInt("DATA_TYPE"));

    assertTrue(rs.next());
    assertEquals("metadatatest", rs.getString("TABLE_NAME"));
    assertEquals("updated", rs.getString("COLUMN_NAME"));
    assertEquals(java.sql.Types.TIMESTAMP, rs.getInt("DATA_TYPE"));
  }

  @Test
  public void testCrossReference() throws Exception {
    Connection con1 = TestUtil.openDB();

    TestUtil.createTable(con1, "vv", "a int not null, b int not null, primary key ( a, b )");

    TestUtil.createTable(con1, "ww",
        "m int not null, n int not null, primary key ( m, n ), foreign key ( m, n ) references vv ( a, b )");


    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    ResultSet rs = dbmd.getCrossReference(null, "", "vv", null, "", "ww");

    for (int j = 1; rs.next(); j++) {

      String pkTableName = rs.getString("PKTABLE_NAME");
      assertEquals("vv", pkTableName);

      String pkColumnName = rs.getString("PKCOLUMN_NAME");
      assertTrue(pkColumnName.equals("a") || pkColumnName.equals("b"));

      String fkTableName = rs.getString("FKTABLE_NAME");
      assertEquals("ww", fkTableName);

      String fkColumnName = rs.getString("FKCOLUMN_NAME");
      assertTrue(fkColumnName.equals("m") || fkColumnName.equals("n"));

      String fkName = rs.getString("FK_NAME");
      assertEquals("ww_m_fkey", fkName);

      String pkName = rs.getString("PK_NAME");
      assertEquals("vv_pkey", pkName);

      int keySeq = rs.getInt("KEY_SEQ");
      assertEquals(j, keySeq);
    }


    TestUtil.dropTable(con1, "vv");
    TestUtil.dropTable(con1, "ww");
    TestUtil.closeDB(con1);
  }

  @Test
  public void testForeignKeyActions() throws Exception {
    Connection conn = TestUtil.openDB();
    TestUtil.createTable(conn, "pkt", "id int primary key");
    TestUtil.createTable(conn, "fkt1",
        "id int references pkt on update restrict on delete cascade");
    TestUtil.createTable(conn, "fkt2",
        "id int references pkt on update set null on delete set default");
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getImportedKeys(null, "", "fkt1");
    assertTrue(rs.next());
    assertTrue(rs.getInt("UPDATE_RULE") == DatabaseMetaData.importedKeyRestrict);
    assertTrue(rs.getInt("DELETE_RULE") == DatabaseMetaData.importedKeyCascade);
    rs.close();

    rs = dbmd.getImportedKeys(null, "", "fkt2");
    assertTrue(rs.next());
    assertTrue(rs.getInt("UPDATE_RULE") == DatabaseMetaData.importedKeySetNull);
    assertTrue(rs.getInt("DELETE_RULE") == DatabaseMetaData.importedKeySetDefault);
    rs.close();

    TestUtil.dropTable(conn, "fkt2");
    TestUtil.dropTable(conn, "fkt1");
    TestUtil.dropTable(conn, "pkt");
    TestUtil.closeDB(conn);
  }

  @Test
  public void testForeignKeysToUniqueIndexes() throws Exception {
    Connection con1 = TestUtil.openDB();
    TestUtil.createTable(con1, "pkt",
        "a int not null, b int not null, CONSTRAINT pkt_pk_a PRIMARY KEY (a), CONSTRAINT pkt_un_b UNIQUE (b)");
    TestUtil.createTable(con1, "fkt",
        "c int, d int, CONSTRAINT fkt_fk_c FOREIGN KEY (c) REFERENCES pkt(b)");

    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getImportedKeys("", "", "fkt");
    int j = 0;
    for (; rs.next(); j++) {
      assertTrue("pkt".equals(rs.getString("PKTABLE_NAME")));
      assertTrue("fkt".equals(rs.getString("FKTABLE_NAME")));
      assertTrue("pkt_un_b".equals(rs.getString("PK_NAME")));
      assertTrue("b".equals(rs.getString("PKCOLUMN_NAME")));
    }
    assertTrue(j == 1);

    TestUtil.dropTable(con1, "fkt");
    TestUtil.dropTable(con1, "pkt");
    con1.close();
  }

  @Test
  public void testMultiColumnForeignKeys() throws Exception {
    Connection con1 = TestUtil.openDB();
    TestUtil.createTable(con1, "pkt",
        "a int not null, b int not null, CONSTRAINT pkt_pk PRIMARY KEY (a,b)");
    TestUtil.createTable(con1, "fkt",
        "c int, d int, CONSTRAINT fkt_fk_pkt FOREIGN KEY (c,d) REFERENCES pkt(b,a)");

    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getImportedKeys("", "", "fkt");
    int j = 0;
    for (; rs.next(); j++) {
      assertTrue("pkt".equals(rs.getString("PKTABLE_NAME")));
      assertTrue("fkt".equals(rs.getString("FKTABLE_NAME")));
      assertTrue(j + 1 == rs.getInt("KEY_SEQ"));
      if (j == 0) {
        assertTrue("b".equals(rs.getString("PKCOLUMN_NAME")));
        assertTrue("c".equals(rs.getString("FKCOLUMN_NAME")));
      } else {
        assertTrue("a".equals(rs.getString("PKCOLUMN_NAME")));
        assertTrue("d".equals(rs.getString("FKCOLUMN_NAME")));
      }
    }
    assertTrue(j == 2);

    TestUtil.dropTable(con1, "fkt");
    TestUtil.dropTable(con1, "pkt");
    con1.close();
  }

  @Test
  public void testSameTableForeignKeys() throws Exception {
    Connection con1 = TestUtil.openDB();

    TestUtil.createTable(con1, "person",
        "FIRST_NAME character varying(100) NOT NULL," + "LAST_NAME character varying(100) NOT NULL,"
            + "FIRST_NAME_PARENT_1 character varying(100),"
            + "LAST_NAME_PARENT_1 character varying(100),"
            + "FIRST_NAME_PARENT_2 character varying(100),"
            + "LAST_NAME_PARENT_2 character varying(100),"
            + "CONSTRAINT PERSON_pkey PRIMARY KEY (FIRST_NAME , LAST_NAME ),"
            + "CONSTRAINT PARENT_1_fkey FOREIGN KEY (FIRST_NAME_PARENT_1, LAST_NAME_PARENT_1)"
            + "REFERENCES PERSON (FIRST_NAME, LAST_NAME) MATCH SIMPLE "
            + "ON UPDATE CASCADE ON DELETE CASCADE,"
            + "CONSTRAINT PARENT_2_fkey FOREIGN KEY (FIRST_NAME_PARENT_2, LAST_NAME_PARENT_2)"
            + "REFERENCES PERSON (FIRST_NAME, LAST_NAME) MATCH SIMPLE "
            + "ON UPDATE CASCADE ON DELETE CASCADE");


    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getImportedKeys(null, "", "person");

    final List<String> fkNames = new ArrayList<String>();

    int lastFieldCount = -1;
    while (rs.next()) {
      // destination table (all foreign keys point to the same)
      String pkTableName = rs.getString("PKTABLE_NAME");
      assertEquals("person", pkTableName);

      // destination fields
      String pkColumnName = rs.getString("PKCOLUMN_NAME");
      assertTrue("first_name".equals(pkColumnName) || "last_name".equals(pkColumnName));

      // source table (all foreign keys are in the same)
      String fkTableName = rs.getString("FKTABLE_NAME");
      assertEquals("person", fkTableName);

      // foreign key name
      String fkName = rs.getString("FK_NAME");
      // sequence number within the foreign key
      int seq = rs.getInt("KEY_SEQ");
      if (seq == 1) {
        // begin new foreign key
        assertFalse(fkNames.contains(fkName));
        fkNames.add(fkName);
        // all foreign keys have 2 fields
        assertTrue(lastFieldCount < 0 || lastFieldCount == 2);
      } else {
        // continue foreign key, i.e. fkName matches the last foreign key
        assertEquals(fkNames.get(fkNames.size() - 1), fkName);
        // see always increases by 1
        assertTrue(seq == lastFieldCount + 1);
      }
      lastFieldCount = seq;
    }
    // there's more than one foreign key from a table to another
    assertEquals(2, fkNames.size());

    TestUtil.dropTable(con1, "person");
    TestUtil.closeDB(con1);


  }

  @Test
  public void testForeignKeys() throws Exception {
    Connection con1 = TestUtil.openDB();
    TestUtil.createTable(con1, "people", "id int4 primary key, name text");
    TestUtil.createTable(con1, "policy", "id int4 primary key, name text");

    TestUtil.createTable(con1, "users",
        "id int4 primary key, people_id int4, policy_id int4,"
            + "CONSTRAINT people FOREIGN KEY (people_id) references people(id),"
            + "constraint policy FOREIGN KEY (policy_id) references policy(id)");


    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    ResultSet rs = dbmd.getImportedKeys(null, "", "users");
    int j = 0;
    for (; rs.next(); j++) {

      String pkTableName = rs.getString("PKTABLE_NAME");
      assertTrue(pkTableName.equals("people") || pkTableName.equals("policy"));

      String pkColumnName = rs.getString("PKCOLUMN_NAME");
      assertEquals("id", pkColumnName);

      String fkTableName = rs.getString("FKTABLE_NAME");
      assertEquals("users", fkTableName);

      String fkColumnName = rs.getString("FKCOLUMN_NAME");
      assertTrue(fkColumnName.equals("people_id") || fkColumnName.equals("policy_id"));

      String fkName = rs.getString("FK_NAME");
      assertTrue(fkName.startsWith("people") || fkName.startsWith("policy"));

      String pkName = rs.getString("PK_NAME");
      assertTrue(pkName.equals("people_pkey") || pkName.equals("policy_pkey"));

    }

    assertTrue(j == 2);

    rs = dbmd.getExportedKeys(null, "", "people");

    // this is hacky, but it will serve the purpose
    assertTrue(rs.next());

    assertEquals("people", rs.getString("PKTABLE_NAME"));
    assertEquals("id", rs.getString("PKCOLUMN_NAME"));

    assertEquals("users", rs.getString("FKTABLE_NAME"));
    assertEquals("people_id", rs.getString("FKCOLUMN_NAME"));

    assertTrue(rs.getString("FK_NAME").startsWith("people"));


    TestUtil.dropTable(con1, "users");
    TestUtil.dropTable(con1, "people");
    TestUtil.dropTable(con1, "policy");
    TestUtil.closeDB(con1);
  }

  @Test
  public void testColumns() throws SQLException {
    // At the moment just test that no exceptions are thrown KJ
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getColumns(null, null, "pg_class", null);
    rs.close();
  }

  @Test
  public void testDroppedColumns() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4)) {
      return;
    }

    Statement stmt = con.createStatement();
    stmt.execute("ALTER TABLE metadatatest DROP name");
    stmt.execute("ALTER TABLE metadatatest DROP colour");
    stmt.close();

    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns(null, null, "metadatatest", null);

    assertTrue(rs.next());
    assertEquals("id", rs.getString("COLUMN_NAME"));
    assertEquals(1, rs.getInt("ORDINAL_POSITION"));

    assertTrue(rs.next());
    assertEquals("updated", rs.getString("COLUMN_NAME"));
    assertEquals(2, rs.getInt("ORDINAL_POSITION"));

    assertTrue(rs.next());
    assertEquals("quest", rs.getString("COLUMN_NAME"));
    assertEquals(3, rs.getInt("ORDINAL_POSITION"));

    rs.close();

    rs = dbmd.getColumns(null, null, "metadatatest", "quest");
    assertTrue(rs.next());
    assertEquals("quest", rs.getString("COLUMN_NAME"));
    assertEquals(3, rs.getInt("ORDINAL_POSITION"));
    assertTrue(!rs.next());
    rs.close();
  }

  @Test
  public void testSerialColumns() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns(null, null, "sercoltest", null);
    int rownum = 0;
    while (rs.next()) {
      assertEquals("sercoltest", rs.getString("TABLE_NAME"));
      assertEquals(rownum + 1, rs.getInt("ORDINAL_POSITION"));
      if (rownum == 0) {
        assertEquals("int4", rs.getString("TYPE_NAME"));
      } else if (rownum == 1) {
        assertEquals("serial", rs.getString("TYPE_NAME"));
      } else if (rownum == 2) {
        assertEquals("bigserial", rs.getString("TYPE_NAME"));
      }
      rownum++;
    }
    assertEquals(3, rownum);
    rs.close();
  }

  @Test
  public void testColumnPrivileges() throws SQLException {
    // At the moment just test that no exceptions are thrown KJ
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getColumnPrivileges(null, null, "pg_statistic", null);
    rs.close();
  }

  @Test
  public void testTablePrivileges() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getTablePrivileges(null, null, "metadatatest");
    boolean l_foundSelect = false;
    while (rs.next()) {
      if (rs.getString("GRANTEE").equals(TestUtil.getUser())
          && rs.getString("PRIVILEGE").equals("SELECT")) {
        l_foundSelect = true;
      }
    }
    rs.close();
    // Test that the table owner has select priv
    assertTrue("Couldn't find SELECT priv on table metadatatest for " + TestUtil.getUser(),
        l_foundSelect);
  }

  @Test
  public void testNoTablePrivileges() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("REVOKE ALL ON metadatatest FROM PUBLIC");
    stmt.execute("REVOKE ALL ON metadatatest FROM " + TestUtil.getUser());
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getTablePrivileges(null, null, "metadatatest");
    assertTrue(!rs.next());
  }

  @Test
  public void testPrimaryKeys() throws SQLException {
    // At the moment just test that no exceptions are thrown KJ
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getPrimaryKeys(null, null, "pg_class");
    rs.close();
  }

  @Test
  public void testIndexInfo() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("create index idx_id on metadatatest (id)");
    stmt.execute("create index idx_func_single on metadatatest (upper(colour))");
    stmt.execute("create unique index idx_un_id on metadatatest(id)");
    stmt.execute("create index idx_func_multi on metadatatest (upper(colour), upper(quest))");
    stmt.execute("create index idx_func_mixed on metadatatest (colour, upper(quest))");

    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getIndexInfo(null, null, "metadatatest", false, false);

    assertTrue(rs.next());
    assertEquals("idx_un_id", rs.getString("INDEX_NAME"));
    assertEquals(1, rs.getInt("ORDINAL_POSITION"));
    assertEquals("id", rs.getString("COLUMN_NAME"));
    assertTrue(!rs.getBoolean("NON_UNIQUE"));

    assertTrue(rs.next());
    assertEquals("idx_func_mixed", rs.getString("INDEX_NAME"));
    assertEquals(1, rs.getInt("ORDINAL_POSITION"));
    assertEquals("colour", rs.getString("COLUMN_NAME"));

    assertTrue(rs.next());
    assertEquals("idx_func_mixed", rs.getString("INDEX_NAME"));
    assertEquals(2, rs.getInt("ORDINAL_POSITION"));
    assertEquals("upper(quest)", rs.getString("COLUMN_NAME"));

    assertTrue(rs.next());
    assertEquals("idx_func_multi", rs.getString("INDEX_NAME"));
    assertEquals(1, rs.getInt("ORDINAL_POSITION"));
    assertEquals("upper(colour)", rs.getString("COLUMN_NAME"));

    assertTrue(rs.next());
    assertEquals("idx_func_multi", rs.getString("INDEX_NAME"));
    assertEquals(2, rs.getInt("ORDINAL_POSITION"));
    assertEquals("upper(quest)", rs.getString("COLUMN_NAME"));

    assertTrue(rs.next());
    assertEquals("idx_func_single", rs.getString("INDEX_NAME"));
    assertEquals(1, rs.getInt("ORDINAL_POSITION"));
    assertEquals("upper(colour)", rs.getString("COLUMN_NAME"));

    assertTrue(rs.next());
    assertEquals("idx_id", rs.getString("INDEX_NAME"));
    assertEquals(1, rs.getInt("ORDINAL_POSITION"));
    assertEquals("id", rs.getString("COLUMN_NAME"));
    assertTrue(rs.getBoolean("NON_UNIQUE"));

    assertTrue(!rs.next());

    rs.close();
  }

  @Test
  public void testNotNullDomainColumn() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns("", "", "domaintable", "");
    assertTrue(rs.next());
    assertEquals("id", rs.getString("COLUMN_NAME"));
    assertEquals("NO", rs.getString("IS_NULLABLE"));
    assertTrue(!rs.next());
  }

  @Test
  public void testAscDescIndexInfo() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3)) {
      return;
    }

    Statement stmt = con.createStatement();
    stmt.execute("CREATE INDEX idx_a_d ON metadatatest (id ASC, quest DESC)");
    stmt.close();

    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getIndexInfo(null, null, "metadatatest", false, false);

    assertTrue(rs.next());
    assertEquals("idx_a_d", rs.getString("INDEX_NAME"));
    assertEquals("id", rs.getString("COLUMN_NAME"));
    assertEquals("A", rs.getString("ASC_OR_DESC"));


    assertTrue(rs.next());
    assertEquals("idx_a_d", rs.getString("INDEX_NAME"));
    assertEquals("quest", rs.getString("COLUMN_NAME"));
    assertEquals("D", rs.getString("ASC_OR_DESC"));
  }

  @Test
  public void testPartialIndexInfo() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("create index idx_p_name_id on metadatatest (name) where id > 5");
    stmt.close();

    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getIndexInfo(null, null, "metadatatest", false, false);

    assertTrue(rs.next());
    assertEquals("idx_p_name_id", rs.getString("INDEX_NAME"));
    assertEquals(1, rs.getInt("ORDINAL_POSITION"));
    assertEquals("name", rs.getString("COLUMN_NAME"));
    assertEquals("(id > 5)", rs.getString("FILTER_CONDITION"));
    assertTrue(rs.getBoolean("NON_UNIQUE"));

    rs.close();
  }

  @Test
  public void testTableTypes() throws SQLException {
    // At the moment just test that no exceptions are thrown KJ
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getTableTypes();
    rs.close();
  }

  @Test
  public void testFuncWithoutNames() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getProcedureColumns(null, null, "f1", null);

    assertTrue(rs.next());
    assertEquals("returnValue", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnReturn, rs.getInt(5));

    assertTrue(rs.next());
    assertEquals("$1", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnIn, rs.getInt(5));
    assertEquals(Types.INTEGER, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("$2", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnIn, rs.getInt(5));
    assertEquals(Types.VARCHAR, rs.getInt(6));

    assertTrue(!rs.next());

    rs.close();
  }

  @Test
  public void testFuncWithNames() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getProcedureColumns(null, null, "f2", null);

    assertTrue(rs.next());

    assertTrue(rs.next());
    assertEquals("a", rs.getString(4));

    assertTrue(rs.next());
    assertEquals("b", rs.getString(4));

    assertTrue(!rs.next());

    rs.close();
  }

  @Test
  public void testFuncWithDirection() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getProcedureColumns(null, null, "f3", null);

    assertTrue(rs.next());
    assertEquals("a", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnIn, rs.getInt(5));
    assertEquals(Types.INTEGER, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("b", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnInOut, rs.getInt(5));
    assertEquals(Types.VARCHAR, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("c", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnOut, rs.getInt(5));
    assertEquals(Types.TIMESTAMP, rs.getInt(6));

    rs.close();
  }

  @Test
  public void testFuncReturningComposite() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getProcedureColumns(null, null, "f4", null);

    assertTrue(rs.next());
    assertEquals("$1", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnIn, rs.getInt(5));
    assertEquals(Types.INTEGER, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("id", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnResult, rs.getInt(5));
    assertEquals(Types.INTEGER, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("name", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnResult, rs.getInt(5));
    assertEquals(Types.VARCHAR, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("updated", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnResult, rs.getInt(5));
    assertEquals(Types.TIMESTAMP, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("colour", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnResult, rs.getInt(5));
    assertEquals(Types.VARCHAR, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("quest", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnResult, rs.getInt(5));
    assertEquals(Types.VARCHAR, rs.getInt(6));

    assertTrue(!rs.next());
    rs.close();
  }

  @Test
  public void testFuncReturningTable() throws Exception {
    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4)) {
      return;
    }
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getProcedureColumns(null, null, "f5", null);
    assertTrue(rs.next());
    assertEquals("returnValue", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnReturn, rs.getInt(5));
    assertEquals(Types.INTEGER, rs.getInt(6));
    assertTrue(rs.next());
    assertEquals("i", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnReturn, rs.getInt(5));
    assertEquals(Types.INTEGER, rs.getInt(6));
    assertTrue(!rs.next());
    rs.close();
  }

  @Test
  public void testVersionColumns() throws SQLException {
    // At the moment just test that no exceptions are thrown KJ
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getVersionColumns(null, null, "pg_class");
    rs.close();
  }

  @Test
  public void testBestRowIdentifier() throws SQLException {
    // At the moment just test that no exceptions are thrown KJ
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs =
        dbmd.getBestRowIdentifier(null, null, "pg_type", DatabaseMetaData.bestRowSession, false);
    rs.close();
  }

  @Test
  public void testProcedures() throws SQLException {
    // At the moment just test that no exceptions are thrown KJ
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getProcedures(null, null, null);
    rs.close();
  }

  @Test
  public void testCatalogs() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getCatalogs();
    assertTrue(rs.next());
    assertEquals(con.getCatalog(), rs.getString(1));
    assertTrue(!rs.next());
  }

  @Test
  public void testSchemas() throws Exception {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    ResultSet rs = dbmd.getSchemas();
    boolean foundPublic = false;
    boolean foundEmpty = false;
    boolean foundPGCatalog = false;
    int count;

    for (count = 0; rs.next(); count++) {
      String schema = rs.getString("TABLE_SCHEM");
      if ("public".equals(schema)) {
        foundPublic = true;
      } else if ("".equals(schema)) {
        foundEmpty = true;
      } else if ("pg_catalog".equals(schema)) {
        foundPGCatalog = true;
      }
    }
    rs.close();
    assertTrue(count >= 2);
    assertTrue(foundPublic);
    assertTrue(foundPGCatalog);
    assertTrue(!foundEmpty);
  }

  @Test
  public void testEscaping() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getTables(null, null, "a'", new String[]{"TABLE"});
    assertTrue(rs.next());
    rs = dbmd.getTables(null, null, "a\\\\", new String[]{"TABLE"});
    assertTrue(rs.next());
    rs = dbmd.getTables(null, null, "a\\", new String[]{"TABLE"});
    assertTrue(!rs.next());
  }

  @Test
  public void testSearchStringEscape() throws Exception {
    DatabaseMetaData dbmd = con.getMetaData();
    String pattern = dbmd.getSearchStringEscape() + "_";
    PreparedStatement pstmt = con.prepareStatement("SELECT 'a' LIKE ?, '_' LIKE ?");
    pstmt.setString(1, pattern);
    pstmt.setString(2, pattern);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertTrue(!rs.getBoolean(1));
    assertTrue(rs.getBoolean(2));
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetUDTQualified() throws Exception {
    Statement stmt = null;
    try {
      stmt = con.createStatement();
      stmt.execute("create schema jdbc");
      stmt.execute("create type jdbc.testint8 as (i int8)");
      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getUDTs(null, null, "jdbc.testint8", null);
      assertTrue(rs.next());
      String cat;
      String schema;
      String typeName;
      String remarks;
      String className;
      int dataType;
      int baseType;

      cat = rs.getString("type_cat");
      schema = rs.getString("type_schem");
      typeName = rs.getString("type_name");
      className = rs.getString("class_name");
      dataType = rs.getInt("data_type");
      remarks = rs.getString("remarks");
      baseType = rs.getInt("base_type");
      assertEquals("type name ", "testint8", typeName);
      assertEquals("schema name ", "jdbc", schema);

      // now test to see if the fully qualified stuff works as planned
      rs = dbmd.getUDTs("catalog", "public", "catalog.jdbc.testint8", null);
      assertTrue(rs.next());
      cat = rs.getString("type_cat");
      schema = rs.getString("type_schem");
      typeName = rs.getString("type_name");
      className = rs.getString("class_name");
      dataType = rs.getInt("data_type");
      remarks = rs.getString("remarks");
      baseType = rs.getInt("base_type");
      assertEquals("type name ", "testint8", typeName);
      assertEquals("schema name ", "jdbc", schema);
    } finally {
      try {
        if (stmt != null) {
          stmt.close();
        }
        stmt = con.createStatement();
        stmt.execute("drop type jdbc.testint8");
        stmt.execute("drop schema jdbc");
      } catch (Exception ex) {
      }
    }

  }

  @Test
  public void testGetUDT1() throws Exception {
    try {
      Statement stmt = con.createStatement();
      stmt.execute("create domain testint8 as int8");
      stmt.execute("comment on domain testint8 is 'jdbc123'");
      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getUDTs(null, null, "testint8", null);
      assertTrue(rs.next());
      String cat;
      String schema;
      String typeName;
      String remarks;
      String className;
      int dataType;
      int baseType;

      cat = rs.getString("type_cat");
      schema = rs.getString("type_schem");
      typeName = rs.getString("type_name");
      className = rs.getString("class_name");
      dataType = rs.getInt("data_type");
      remarks = rs.getString("remarks");

      baseType = rs.getInt("base_type");
      assertTrue("base type", !rs.wasNull());
      assertEquals("data type", Types.DISTINCT, dataType);
      assertEquals("type name ", "testint8", typeName);
      assertEquals("remarks", "jdbc123", remarks);

    } finally {
      try {
        Statement stmt = con.createStatement();
        stmt.execute("drop domain testint8");
      } catch (Exception ex) {
      }
    }
  }


  @Test
  public void testGetUDT2() throws Exception {
    try {
      Statement stmt = con.createStatement();
      stmt.execute("create domain testint8 as int8");
      stmt.execute("comment on domain testint8 is 'jdbc123'");
      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getUDTs(null, null, "testint8", new int[]{Types.DISTINCT, Types.STRUCT});
      assertTrue(rs.next());
      String cat;
      String schema;
      String typeName;
      String remarks;
      String className;
      int dataType;
      int baseType;

      cat = rs.getString("type_cat");
      schema = rs.getString("type_schem");
      typeName = rs.getString("type_name");
      className = rs.getString("class_name");
      dataType = rs.getInt("data_type");
      remarks = rs.getString("remarks");

      baseType = rs.getInt("base_type");
      assertTrue("base type", !rs.wasNull());
      assertEquals("data type", Types.DISTINCT, dataType);
      assertEquals("type name ", "testint8", typeName);
      assertEquals("remarks", "jdbc123", remarks);

    } finally {
      try {
        Statement stmt = con.createStatement();
        stmt.execute("drop domain testint8");
      } catch (Exception ex) {
      }
    }
  }

  @Test
  public void testGetUDT3() throws Exception {
    try {
      Statement stmt = con.createStatement();
      stmt.execute("create domain testint8 as int8");
      stmt.execute("comment on domain testint8 is 'jdbc123'");
      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getUDTs(null, null, "testint8", new int[]{Types.DISTINCT});
      assertTrue(rs.next());
      String cat;
      String schema;
      String typeName;
      String remarks;
      String className;
      int dataType;
      int baseType;

      cat = rs.getString("type_cat");
      schema = rs.getString("type_schem");
      typeName = rs.getString("type_name");
      className = rs.getString("class_name");
      dataType = rs.getInt("data_type");
      remarks = rs.getString("remarks");

      baseType = rs.getInt("base_type");
      assertTrue("base type", !rs.wasNull());
      assertEquals("data type", Types.DISTINCT, dataType);
      assertEquals("type name ", "testint8", typeName);
      assertEquals("remarks", "jdbc123", remarks);

    } finally {
      try {
        Statement stmt = con.createStatement();
        stmt.execute("drop domain testint8");
      } catch (Exception ex) {
      }
    }
  }

  @Test
  public void testGetUDT4() throws Exception {
    try {
      Statement stmt = con.createStatement();
      stmt.execute("create type testint8 as (i int8)");
      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getUDTs(null, null, "testint8", null);
      assertTrue(rs.next());
      String cat;
      String schema;
      String typeName;
      String remarks;
      String className;
      int dataType;
      int baseType;

      cat = rs.getString("type_cat");
      schema = rs.getString("type_schem");
      typeName = rs.getString("type_name");
      className = rs.getString("class_name");
      dataType = rs.getInt("data_type");
      remarks = rs.getString("remarks");

      baseType = rs.getInt("base_type");
      assertTrue("base type", rs.wasNull());
      assertEquals("data type", Types.STRUCT, dataType);
      assertEquals("type name ", "testint8", typeName);

    } finally {
      try {
        Statement stmt = con.createStatement();
        stmt.execute("drop type testint8");
      } catch (Exception ex) {
      }
    }
  }

  @Test
  public void testTypes() throws SQLException {
    // https://www.postgresql.org/docs/8.2/static/datatype.html
    List<String> stringTypeList = new ArrayList<String>();
    stringTypeList.addAll(Arrays.asList(new String[]{
        "bit",
        "bool",
        "box",
        "bytea",
        "char",
        "cidr",
        "circle",
        "date",
        "float4",
        "float8",
        "inet",
        "int2",
        "int4",
        "int8",
        "interval",
        "line",
        "lseg",
        "macaddr",
        "money",
        "numeric",
        "path",
        "point",
        "polygon",
        "text",
        "time",
        "timestamp",
        "timestamptz",
        "timetz",
        "varbit",
        "varchar"
    }));
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3)) {
      stringTypeList.add("tsquery");
      stringTypeList.add("tsvector");
      stringTypeList.add("txid_snapshot");
      stringTypeList.add("uuid");
      stringTypeList.add("xml");
    }
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_2)) {
      stringTypeList.add("json");
    }
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_4)) {
      stringTypeList.add("jsonb");
      stringTypeList.add("pg_lsn");
    }

    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getTypeInfo();
    List<String> types = new ArrayList<String>();

    while (rs.next()) {
      types.add(rs.getString("TYPE_NAME"));
    }
    for (String typeName : stringTypeList) {
      assertTrue(types.contains(typeName));
    }

  }

  @Test
  public void testTypeInfoSigned() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getTypeInfo();
    while (rs.next()) {
      if ("int4".equals(rs.getString("TYPE_NAME"))) {
        assertEquals(false, rs.getBoolean("UNSIGNED_ATTRIBUTE"));
      } else if ("float8".equals(rs.getString("TYPE_NAME"))) {
        assertEquals(false, rs.getBoolean("UNSIGNED_ATTRIBUTE"));
      } else if ("text".equals(rs.getString("TYPE_NAME"))) {
        assertEquals(true, rs.getBoolean("UNSIGNED_ATTRIBUTE"));
      }
    }
  }

  @Test
  public void testTypeInfoQuoting() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getTypeInfo();
    while (rs.next()) {
      if ("int4".equals(rs.getString("TYPE_NAME"))) {
        assertNull(rs.getString("LITERAL_PREFIX"));
      } else if ("text".equals(rs.getString("TYPE_NAME"))) {
        assertEquals("'", rs.getString("LITERAL_PREFIX"));
        assertEquals("'", rs.getString("LITERAL_SUFFIX"));
      }
    }
  }

  @Test
  public void testInformationAboutArrayTypes() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns("", "", "arraytable", "");
    assertTrue(rs.next());
    assertEquals("a", rs.getString("COLUMN_NAME"));
    assertEquals(5, rs.getInt("COLUMN_SIZE"));
    assertEquals(2, rs.getInt("DECIMAL_DIGITS"));
    assertTrue(rs.next());
    assertEquals("b", rs.getString("COLUMN_NAME"));
    assertEquals(100, rs.getInt("COLUMN_SIZE"));
    assertTrue(!rs.next());
  }

}
