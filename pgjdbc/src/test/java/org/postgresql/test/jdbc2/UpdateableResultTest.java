/*
 * Copyright (c) 2001, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.PGConnection;
import org.postgresql.test.TestUtil;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.TimeZone;

public class UpdateableResultTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "updateable",
        "id int primary key, name text, notselected text, ts timestamp with time zone, intarr int[]");
    TestUtil.createTable(con, "hasdate", "id int primary key, dt date unique, name text");
    TestUtil.createTable(con, "unique_null_constraint", "u1 int unique, name1 text");
    TestUtil.createTable(con, "uniquekeys", "id int unique not null, id2 int unique, dt date");
    TestUtil.createTable(con, "partialunique", "subject text, target text, success boolean");
    TestUtil.execute("CREATE UNIQUE INDEX tests_success_constraint ON partialunique (subject, target) WHERE success", con);
    TestUtil.createTable(con, "second", "id1 int primary key, name1 text");
    TestUtil.createTable(con, "primaryunique", "id int primary key, name text unique not null, dt date");
    TestUtil.createTable(con, "serialtable", "gen_id serial primary key, name text");
    TestUtil.createTable(con, "compositepktable", "gen_id serial, name text, dec_id serial");
    TestUtil.execute( "alter sequence compositepktable_dec_id_seq increment by 10; alter sequence compositepktable_dec_id_seq restart with 10", con);
    TestUtil.execute( "alter table compositepktable add primary key ( gen_id, dec_id )", con);
    TestUtil.createTable(con, "stream", "id int primary key, asi text, chr text, bin bytea");
    TestUtil.createTable(con, "multicol", "id1 int not null, id2 int not null, val text");
    TestUtil.createTable(con, "nopkmulticol", "id1 int not null, id2 int not null, val text");
    TestUtil.createTable(con, "booltable", "id int not null primary key, b boolean default false");
    TestUtil.execute("insert into booltable (id) values (1)", con);
    TestUtil.execute("insert into uniquekeys(id, id2, dt) values (1, 2, now())", con);

    Statement st2 = con.createStatement();
    // create pk for multicol table
    st2.execute("ALTER TABLE multicol ADD CONSTRAINT multicol_pk PRIMARY KEY (id1, id2)");
    // put some dummy data into second
    st2.execute("insert into second values (1,'anyvalue' )");
    st2.close();
    TestUtil.execute("insert into unique_null_constraint values (1, 'dave')", con);
    TestUtil.execute("insert into unique_null_constraint values (null, 'unknown')", con);
    TestUtil.execute("insert into primaryunique values (1, 'dave', now())", con);

  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "updateable");
    TestUtil.dropTable(con, "second");
    TestUtil.dropTable(con, "serialtable");
    TestUtil.dropTable(con, "compositepktable");
    TestUtil.dropTable(con, "stream");
    TestUtil.dropTable(con, "nopkmulticol");
    TestUtil.dropTable(con, "booltable");
    TestUtil.dropTable(con, "unique_null_constraint");
    TestUtil.dropTable(con, "hasdate");
    TestUtil.dropTable(con, "uniquekeys");
    TestUtil.dropTable(con, "partialunique");
    TestUtil.dropTable(con, "primaryunique");
    super.tearDown();
  }

  @Test
  public void testDeleteRows() throws SQLException {
    Statement st = con.createStatement();
    st.executeUpdate("INSERT INTO second values (2,'two')");
    st.executeUpdate("INSERT INTO second values (3,'three')");
    st.executeUpdate("INSERT INTO second values (4,'four')");
    st.close();

    st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery("select id1,name1 from second order by id1");

    assertTrue(rs.next());
    assertEquals(1, rs.getInt("id1"));
    rs.deleteRow();
    assertTrue(rs.isBeforeFirst());

    assertTrue(rs.next());
    assertTrue(rs.next());
    assertEquals(3, rs.getInt("id1"));
    rs.deleteRow();
    assertEquals(2, rs.getInt("id1"));

    rs.close();
    st.close();
  }

  @Test
  public void testCancelRowUpdates() throws Exception {
    Statement st =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery("select * from second");

    // make sure we're dealing with the correct row.
    rs.first();
    assertEquals(1, rs.getInt(1));
    assertEquals("anyvalue", rs.getString(2));

    // update, cancel and make sure nothings changed.
    rs.updateInt(1, 99);
    rs.cancelRowUpdates();
    assertEquals(1, rs.getInt(1));
    assertEquals("anyvalue", rs.getString(2));

    // real update
    rs.updateInt(1, 999);
    rs.updateRow();
    assertEquals(999, rs.getInt(1));
    assertEquals("anyvalue", rs.getString(2));

    // scroll some and make sure the update is still there
    rs.beforeFirst();
    rs.next();
    assertEquals(999, rs.getInt(1));
    assertEquals("anyvalue", rs.getString(2));

    // make sure the update got to the db and the driver isn't lying to us.
    rs.close();
    rs = st.executeQuery("select * from second");
    rs.first();
    assertEquals(999, rs.getInt(1));
    assertEquals("anyvalue", rs.getString(2));

    rs.close();
    st.close();
  }

  private void checkPositioning(ResultSet rs) throws SQLException {
    try {
      rs.getInt(1);
      fail("Can't use an incorrectly positioned result set.");
    } catch (SQLException sqle) {
    }

    try {
      rs.updateInt(1, 2);
      fail("Can't use an incorrectly positioned result set.");
    } catch (SQLException sqle) {
    }

    try {
      rs.updateRow();
      fail("Can't use an incorrectly positioned result set.");
    } catch (SQLException sqle) {
    }

    try {
      rs.deleteRow();
      fail("Can't use an incorrectly positioned result set.");
    } catch (SQLException sqle) {
    }
  }

  @Test
  public void testPositioning() throws SQLException {
    Statement stmt =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = stmt.executeQuery("SELECT id1,name1 FROM second");

    checkPositioning(rs);

    assertTrue(rs.next());
    rs.beforeFirst();
    checkPositioning(rs);

    rs.afterLast();
    checkPositioning(rs);

    rs.beforeFirst();
    assertTrue(rs.next());
    assertTrue(!rs.next());
    checkPositioning(rs);

    rs.afterLast();
    assertTrue(rs.previous());
    assertTrue(!rs.previous());
    checkPositioning(rs);

    rs.close();
    stmt.close();
  }

  @Test
  public void testReturnSerial() throws Exception {
    final String ole = "Ole";

    Statement st = null;
    ResultSet rs = null;
    try {
      st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
      rs = st.executeQuery("SELECT * FROM serialtable");

      rs.moveToInsertRow();
      rs.updateString("name", ole);
      rs.insertRow();

      assertTrue(rs.first());
      assertEquals(1, rs.getInt("gen_id"));
      assertEquals(ole, rs.getString("name"));

    } finally {
      TestUtil.closeQuietly(rs);
      TestUtil.closeQuietly(st);
    }

    final String ole2 = "OleOle";
    try {
      st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
      rs = st.executeQuery("SELECT name, gen_id FROM serialtable");

      rs.moveToInsertRow();
      rs.updateString("name", ole2);
      rs.insertRow();

      assertTrue(rs.first());
      assertEquals(1, rs.getInt("gen_id"));
      assertEquals(ole, rs.getString("name"));

      assertTrue(rs.last());
      assertEquals(2, rs.getInt("gen_id"));
      assertEquals(ole2, rs.getString("name"));

    } finally {
      TestUtil.closeQuietly(rs);
      TestUtil.closeQuietly(st);
    }

    final String dec = "Dec";
    try {
      st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
      rs = st.executeQuery("SELECT * FROM compositepktable");

      rs.moveToInsertRow();
      rs.updateString("name", dec);
      rs.insertRow();

      assertTrue(rs.first());
      assertEquals(1, rs.getInt("gen_id"));
      assertEquals(dec, rs.getString("name"));
      assertEquals(10, rs.getInt("dec_id"));

      rs.moveToInsertRow();
      rs.updateString("name", dec);
      rs.insertRow();

      assertTrue(rs.last());
      assertEquals(2, rs.getInt("gen_id"));
      assertEquals(dec, rs.getString("name"));
      assertEquals(20, rs.getInt("dec_id"));

    } finally {
      TestUtil.closeQuietly(rs);
      TestUtil.closeQuietly(st);
    }
  }

  @Test
  public void testUpdateTimestamp() throws SQLException {
    TimeZone origTZ = TimeZone.getDefault();
    try {
      // We choose a timezone which has a partial hour portion
      // Asia/Tehran is +3:30
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tehran"));
      Timestamp ts = Timestamp.valueOf("2006-11-20 16:17:18");

      Statement stmt =
          con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
      ResultSet rs = stmt.executeQuery("SELECT id, ts FROM updateable");
      rs.moveToInsertRow();
      rs.updateInt(1, 1);
      rs.updateTimestamp(2, ts);
      rs.insertRow();
      rs.first();
      assertEquals(ts, rs.getTimestamp(2));
    } finally {
      TimeZone.setDefault(origTZ);
    }
  }

  @Test
  public void testUpdateStreams() throws SQLException, UnsupportedEncodingException {
    assumeByteaSupported();
    String string = "Hello";
    byte[] bytes = new byte[]{0, '\\', (byte) 128, (byte) 255};

    Statement stmt =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = stmt.executeQuery("SELECT id, asi, chr, bin FROM stream");

    rs.moveToInsertRow();
    rs.updateInt(1, 1);
    rs.updateAsciiStream("asi", null, 17);
    rs.updateCharacterStream("chr", null, 81);
    rs.updateBinaryStream("bin", null, 0);
    rs.insertRow();

    rs.moveToInsertRow();
    rs.updateInt(1, 3);
    rs.updateAsciiStream("asi", new ByteArrayInputStream(string.getBytes("US-ASCII")), 5);
    rs.updateCharacterStream("chr", new StringReader(string), 5);
    rs.updateBinaryStream("bin", new ByteArrayInputStream(bytes), bytes.length);
    rs.insertRow();

    rs.beforeFirst();
    rs.next();

    assertEquals(1, rs.getInt(1));
    assertNull(rs.getString(2));
    assertNull(rs.getString(3));
    assertNull(rs.getBytes(4));

    rs.updateInt("id", 2);
    rs.updateAsciiStream("asi", new ByteArrayInputStream(string.getBytes("US-ASCII")), 5);
    rs.updateCharacterStream("chr", new StringReader(string), 5);
    rs.updateBinaryStream("bin", new ByteArrayInputStream(bytes), bytes.length);
    rs.updateRow();

    assertEquals(2, rs.getInt(1));
    assertEquals(string, rs.getString(2));
    assertEquals(string, rs.getString(3));
    assertArrayEquals(bytes, rs.getBytes(4));

    rs.refreshRow();

    assertEquals(2, rs.getInt(1));
    assertEquals(string, rs.getString(2));
    assertEquals(string, rs.getString(3));
    assertArrayEquals(bytes, rs.getBytes(4));

    rs.next();

    assertEquals(3, rs.getInt(1));
    assertEquals(string, rs.getString(2));
    assertEquals(string, rs.getString(3));
    assertArrayEquals(bytes, rs.getBytes(4));

    rs.close();
    stmt.close();
  }

  @Test
  public void testZeroRowResult() throws SQLException {
    Statement st =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery("select * from updateable WHERE 0 > 1");
    assertTrue(!rs.next());
    rs.moveToInsertRow();
    rs.moveToCurrentRow();
  }

  @Test
  public void testUpdateable() throws SQLException {
    Statement st =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery("select * from updateable");
    assertNotNull(rs);
    rs.moveToInsertRow();
    rs.updateInt(1, 1);
    rs.updateString(2, "jake");
    rs.updateString(3, "avalue");
    rs.insertRow();
    rs.first();

    rs.updateInt("id", 2);
    rs.updateString("name", "dave");
    rs.updateRow();

    assertEquals(2, rs.getInt("id"));
    assertEquals("dave", rs.getString("name"));
    assertEquals("avalue", rs.getString("notselected"));

    rs.deleteRow();
    rs.moveToInsertRow();
    rs.updateInt("id", 3);
    rs.updateString("name", "paul");

    rs.insertRow();

    try {
      rs.refreshRow();
      fail("Can't refresh when on the insert row.");
    } catch (SQLException sqle) {
    }

    assertEquals(3, rs.getInt("id"));
    assertEquals("paul", rs.getString("name"));
    assertNull(rs.getString("notselected"));

    rs.close();

    rs = st.executeQuery("select id1, id, name, name1 from updateable, second");
    try {
      while (rs.next()) {
        rs.updateInt("id", 2);
        rs.updateString("name", "dave");
        rs.updateRow();
      }
      fail("should not get here, update should fail");
    } catch (SQLException ex) {
    }

    rs = st.executeQuery("select * from updateable");
    assertTrue(rs.first());
    rs.updateInt("id", 3);
    rs.updateString("name", "dave3");
    rs.updateRow();
    assertEquals(3, rs.getInt("id"));
    assertEquals("dave3", rs.getString("name"));

    rs.moveToInsertRow();
    rs.updateInt("id", 4);
    rs.updateString("name", "dave4");

    rs.insertRow();
    rs.updateInt("id", 5);
    rs.updateString("name", "dave5");
    rs.insertRow();

    rs.moveToCurrentRow();
    assertEquals(3, rs.getInt("id"));
    assertEquals("dave3", rs.getString("name"));

    assertTrue(rs.next());
    assertEquals(4, rs.getInt("id"));
    assertEquals("dave4", rs.getString("name"));

    assertTrue(rs.next());
    assertEquals(5, rs.getInt("id"));
    assertEquals("dave5", rs.getString("name"));

    rs.close();
    st.close();
  }

  @Test
  public void testUpdateDate() throws Exception {
    Date testDate = Date.valueOf("2021-01-01");
    TestUtil.execute("insert into hasdate values (1,'2021-01-01'::date)", con);
    con.setAutoCommit(false);
    String sql = "SELECT * FROM hasdate where id=1";
    ResultSet rs = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE).executeQuery(sql);
    assertTrue(rs.next());
    assertEquals(testDate, rs.getDate("dt"));
    rs.updateDate("dt", Date.valueOf("2020-01-01"));
    rs.updateRow();
    assertEquals(Date.valueOf("2020-01-01"), rs.getDate("dt"));
    con.commit();
    rs = con.createStatement().executeQuery("select dt from hasdate where id=1");
    assertTrue(rs.next());
    assertEquals(Date.valueOf("2020-01-01"), rs.getDate("dt"));
    rs.close();
  }

  @Test
  public void test2193() throws Exception {
    Statement st =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery("select * from updateable");
    assertNotNull(rs);
    rs.moveToInsertRow();
    rs.updateInt(1, 1);
    rs.updateString(2, "jake");
    rs.updateString(3, "avalue");
    rs.insertRow();
    rs.first();

    rs.updateString(2, "bob");
    rs.updateRow();
    rs.refreshRow();
    rs.updateString(2, "jake");
    rs.updateRow();
  }

  @Test
  public void testInsertRowIllegalMethods() throws Exception {
    Statement st =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery("select * from updateable");
    assertNotNull(rs);
    rs.moveToInsertRow();

    try {
      rs.cancelRowUpdates();
      fail("expected an exception when calling cancelRowUpdates() on the insert row");
    } catch (SQLException e) {
    }

    try {
      rs.updateRow();
      fail("expected an exception when calling updateRow() on the insert row");
    } catch (SQLException e) {
    }

    try {
      rs.deleteRow();
      fail("expected an exception when calling deleteRow() on the insert row");
    } catch (SQLException e) {
    }

    try {
      rs.refreshRow();
      fail("expected an exception when calling refreshRow() on the insert row");
    } catch (SQLException e) {
    }

    rs.close();
    st.close();
  }

  @Test
  public void testUpdateablePreparedStatement() throws Exception {
    // No args.
    PreparedStatement st = con.prepareStatement("select * from updateable",
        ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery();
    rs.moveToInsertRow();
    rs.close();
    st.close();

    // With args.
    st = con.prepareStatement("select * from updateable where id = ?",
        ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    st.setInt(1, 1);
    rs = st.executeQuery();
    rs.moveToInsertRow();
    rs.close();
    st.close();
  }

  @Test
  public void testUpdateSelectOnly() throws Exception {
    Statement st =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);

    ResultSet rs = st.executeQuery("select * from only second");
    assertTrue(rs.next());
    rs.updateInt(1, 2);
    rs.updateRow();
  }

  @Test
  public void testUpdateReadOnlyResultSet() throws Exception {
    Statement st =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    ResultSet rs = st.executeQuery("select * from updateable");
    try {
      rs.moveToInsertRow();
      fail("expected an exception when calling moveToInsertRow() on a read-only resultset");
    } catch (SQLException e) {
    }
  }

  @Test
  public void testBadColumnIndexes() throws Exception {
    Statement st =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery("select * from updateable");
    rs.moveToInsertRow();
    try {
      rs.updateInt(0, 1);
      fail("Should have thrown an exception on bad column index.");
    } catch (SQLException sqle) {
    }
    try {
      rs.updateString(1000, "hi");
      fail("Should have thrown an exception on bad column index.");
    } catch (SQLException sqle) {
    }
    try {
      rs.updateNull(1000);
      fail("Should have thrown an exception on bad column index.");
    } catch (SQLException sqle) {
    }
  }

  @Test
  public void testArray() throws SQLException {
    Statement stmt =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    stmt.executeUpdate("INSERT INTO updateable (id, intarr) VALUES (1, '{1,2,3}'::int4[])");
    ResultSet rs = stmt.executeQuery("SELECT id, intarr FROM updateable");
    assertTrue(rs.next());
    rs.updateObject(2, rs.getArray(2));
    rs.updateRow();

    Array arr = rs.getArray(2);
    assertEquals(Types.INTEGER, arr.getBaseType());
    Integer[] intarr = (Integer[]) arr.getArray();
    assertEquals(3, intarr.length);
    assertEquals(1, intarr[0].intValue());
    assertEquals(2, intarr[1].intValue());
    assertEquals(3, intarr[2].intValue());
    rs.close();

    rs = stmt.executeQuery("SELECT id,intarr FROM updateable");
    assertTrue(rs.next());
    arr = rs.getArray(2);
    assertEquals(Types.INTEGER, arr.getBaseType());
    intarr = (Integer[]) arr.getArray();
    assertEquals(3, intarr.length);
    assertEquals(1, intarr[0].intValue());
    assertEquals(2, intarr[1].intValue());
    assertEquals(3, intarr[2].intValue());

    rs.close();
    stmt.close();
  }

  @Test
  public void testMultiColumnUpdateWithoutAllColumns() throws Exception {
    Statement st =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery("select id1,val from multicol");
    try {
      rs.moveToInsertRow();
      fail("Move to insert row succeeded. It should not");
    } catch (SQLException sqle) {
      // Ensure we're reporting that the RS is not updatable.
      assertEquals("24000", sqle.getSQLState());
    } finally {
      TestUtil.closeQuietly(rs);
      TestUtil.closeQuietly(st);
    }
  }

  @Test
  public void testMultiColumnUpdateWithoutPrimaryKey() throws Exception {
    Statement st =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery("select * from nopkmulticol");
    try {
      rs.moveToInsertRow();
      fail("Move to insert row succeeded. It should not");
    } catch (SQLException sqle) {
      // Ensure we're reporting that the RS is not updatable.
      assertEquals("24000", sqle.getSQLState());
    } finally {
      TestUtil.closeQuietly(rs);
      TestUtil.closeQuietly(st);
    }
  }

  @Test
  public void testMultiColumnUpdate() throws Exception {
    Statement st =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    st.executeUpdate("INSERT INTO multicol (id1,id2,val) VALUES (1,2,'val')");

    ResultSet rs = st.executeQuery("SELECT id1, id2, val FROM multicol");
    assertTrue(rs.next());
    assertEquals("val", rs.getString("val"));
    rs.updateString("val", "newval");
    rs.updateRow();
    rs.close();

    rs = st.executeQuery("SELECT id1, id2, val FROM multicol");
    assertTrue(rs.next());
    assertEquals("newval", rs.getString("val"));
    rs.close();
    st.close();
  }

  @Test
  public void simpleAndUpdateableSameQuery() throws Exception {
    PGConnection unwrap = con.unwrap(PGConnection.class);
    Assume.assumeNotNull(unwrap);
    int prepareThreshold = unwrap.getPrepareThreshold();
    String sql = "select * from second where id1=?";
    for (int i = 0; i <= prepareThreshold; i++) {
      PreparedStatement ps = null;
      ResultSet rs = null;
      try {
        ps = con.prepareStatement(sql);
        ps.setInt(1, 1);
        rs = ps.executeQuery();
        rs.next();
        String name1 = rs.getString("name1");
        Assert.assertEquals("anyvalue", name1);
        int id1 = rs.getInt("id1");
        Assert.assertEquals(1, id1);
      } finally {
        TestUtil.closeQuietly(rs);
        TestUtil.closeQuietly(ps);
      }
    }
    // The same SQL, and use updateable ResultSet
    {
      PreparedStatement ps = null;
      ResultSet rs = null;
      try {
        ps = con.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
        ps.setInt(1, 1);
        rs = ps.executeQuery();
        rs.next();
        String name1 = rs.getString("name1");
        Assert.assertEquals("anyvalue", name1);
        int id1 = rs.getInt("id1");
        Assert.assertEquals(1, id1);
        rs.updateString("name1", "updatedValue");
        rs.updateRow();
      } finally {
        TestUtil.closeQuietly(rs);
        TestUtil.closeQuietly(ps);
      }
    }
  }

  @Test
  public void testUpdateBoolean() throws Exception {

    Statement st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery("SELECT * FROM booltable WHERE id=1");
    assertTrue(rs.next());
    assertFalse(rs.getBoolean("b"));
    rs.updateBoolean("b", true);
    rs.updateRow();
    //rs.refreshRow(); //fetches the value stored
    assertTrue(rs.getBoolean("b"));
  }

  @Test
  public void testOidUpdatable() throws Exception {
    Connection privilegedCon = TestUtil.openPrivilegedDB();
    try {
      Statement st = privilegedCon.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
          ResultSet.CONCUR_UPDATABLE);
      ResultSet rs = st.executeQuery("SELECT oid,* FROM pg_class WHERE relname = 'pg_class'");
      assertTrue(rs.next());
      assertTrue(rs.first());
      rs.updateString("relname", "pg_class");
      rs.updateRow();
      rs.close();
      st.close();
    } finally {
      privilegedCon.close();
    }
  }

  @Test
  public void testUniqueWithNullableColumnsNotUpdatable() throws Exception {
    Statement st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery("SELECT u1, name1 from unique_null_constraint");
    assertTrue(rs.next());
    assertTrue(rs.first());
    try {
      rs.updateString("name1", "bob");
      fail("Should have failed since unique column u1 is nullable");
    } catch (SQLException ex) {
      assertEquals("No eligible primary or unique key found for table unique_null_constraint.",
          ex.getMessage());
    }
    rs.close();
    st.close();
  }

  @Test
  public void testPrimaryAndUniqueUpdateableByPrimary() throws Exception {
    Statement st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery("SELECT id, dt from primaryunique");
    assertTrue(rs.next());
    assertTrue(rs.first());
    int id = rs.getInt("id");
    rs.updateDate("dt", Date.valueOf("1999-01-01"));
    rs.updateRow();
    assertFalse(rs.next());
    rs.close();
    rs = st.executeQuery("select dt from primaryunique where id = " + id);
    assertTrue(rs.next());
    assertEquals(Date.valueOf("1999-01-01"), rs.getDate("dt"));
    rs.close();
    st.close();
  }

  @Test
  public void testPrimaryAndUniqueUpdateableByUnique() throws Exception {
    Statement st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery("SELECT name, dt from primaryunique");
    assertTrue(rs.next());
    assertTrue(rs.first());
    String name = rs.getString("name");
    rs.updateDate("dt", Date.valueOf("1999-01-01"));
    rs.updateRow();
    assertFalse(rs.next());
    rs.close();
    rs = st.executeQuery("select dt from primaryunique where name = '" + name + "'");
    assertTrue(rs.next());
    assertEquals(Date.valueOf("1999-01-01"), rs.getDate("dt"));
    rs.close();
    st.close();
  }

  @Test
  public void testUniqueWithNullAndNotNullableColumnUpdateable() throws Exception {
    Statement st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
    int id = 0;
    int id2 = 0;
    ResultSet rs = st.executeQuery("SELECT id, id2, dt from uniquekeys");
    assertTrue(rs.next());
    assertTrue(rs.first());
    id = rs.getInt(("id"));
    id2 = rs.getInt(("id2"));
    rs.updateDate("dt", Date.valueOf("1999-01-01"));
    rs.updateRow();
    rs.close();
    rs = st.executeQuery("select dt from uniquekeys where id = " + id + " and id2 = " + id2);
    assertNotNull(rs);
    assertTrue(rs.next());
    assertEquals(Date.valueOf("1999-01-01"), rs.getDate("dt"));
    rs.close();
    st.close();
  }

  @Test
  public void testUniqueWithNotNullableColumnUpdateable() throws Exception {
    Statement st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
    int id = 0;
    ResultSet rs = st.executeQuery("SELECT id, dt from uniquekeys");
    assertTrue(rs.next());
    assertTrue(rs.first());
    id = rs.getInt(("id"));
    rs.updateDate("dt", Date.valueOf("1999-01-01"));
    rs.updateRow();
    rs.close();
    rs = st.executeQuery("select id, dt from uniquekeys where id = " + id);
    assertNotNull(rs);
    assertTrue(rs.next());
    assertEquals(Date.valueOf("1999-01-01"), rs.getDate("dt"));
    rs.close();
    st.close();
  }

  @Test
  public void testUniqueWithNullableColumnNotUpdateable() throws Exception {
    Statement st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery("SELECT id2, dt from uniquekeys");
    assertTrue(rs.next());
    assertTrue(rs.first());
    try {
      rs.updateDate("dt", Date.valueOf("1999-01-01"));
      fail("Should have failed since id2 is nullable column");
    } catch (SQLException ex) {
      assertEquals("No eligible primary or unique key found for table uniquekeys.",
          ex.getMessage());
    }
    rs.close();
    st.close();
  }

  @Test
  public void testNoUniqueNotUpdateable() throws SQLException {
    Statement st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = st.executeQuery("SELECT dt from uniquekeys");
    assertTrue(rs.next());
    assertTrue(rs.first());
    try {
      rs.updateDate("dt", Date.valueOf("1999-01-01"));
      fail("Should have failed since no UK/PK are in the select statement");
    } catch (SQLException ex) {
      assertEquals("No eligible primary or unique key found for table uniquekeys.",
          ex.getMessage());
    }
    rs.close();
    st.close();
  }
}
