/*
 * Copyright (c) 2001, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
        "id int primary key, name text, notselected text, ts timestamp with time zone, intarr int[]",
        true);
    TestUtil.createTable(con, "second", "id1 int primary key, name1 text");
    TestUtil.createTable(con, "stream", "id int primary key, asi text, chr text, bin bytea");
    TestUtil.createTable(con, "multicol", "id1 int not null, id2 int not null, val text");

    Statement st2 = con.createStatement();
    // create pk for multicol table
    st2.execute("ALTER TABLE multicol ADD CONSTRAINT multicol_pk PRIMARY KEY (id1, id2)");
    // put some dummy data into second
    st2.execute("insert into second values (1,'anyvalue' )");
    st2.close();

  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "updateable");
    TestUtil.dropTable(con, "second");
    TestUtil.dropTable(con, "stream");
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

    rs = st.executeQuery("select oid,* from updateable");
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
    } catch (SQLException sqle) {
      // Ensure we're reporting that the RS is not updatable.
      assertEquals("24000", sqle.getSQLState());
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

}
