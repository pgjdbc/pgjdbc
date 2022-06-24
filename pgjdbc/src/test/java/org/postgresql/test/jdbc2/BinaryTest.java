/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.Oid;
import org.postgresql.core.QueryExecutor;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/**
 * TestCase to test handling of binary types.
 */
public class BinaryTest {
  private Connection con;

  /**
   * Set up the fixture for this testcase: the tables for this test.
   *
   * @throws SQLException if a database error occurs
   */
  @BeforeClass
  public static void setUp() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createTable(con, "test_binary", "id integer,name text,geom point");
      TestUtil.closeDB(con);
    }
  }

  /**
   * Tear down the fixture for this test case.
   *
   * @throws SQLException if a database error occurs
   */
  @AfterClass
  public static void tearDown() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, "test_binary");
      TestUtil.closeDB(con);
    }
  }

  /**
   * Make sure the functions for adding binary transfer OIDs for custom types are correct.
   *
   * @throws SQLException if a database error occurs
   */
  @Test
  public void testBinaryTransferOids() throws SQLException {
    con = TestUtil.openDB();
    QueryExecutor queryExecutor = ((PgConnection) con).getQueryExecutor();
    // get current OIDs (make a copy of them)
    Set<Integer> oidsReceive = new HashSet<Integer>(queryExecutor.getBinaryReceiveOids());
    Set<Integer> oidsSend = new HashSet<Integer>(queryExecutor.getBinarySendOids());
    // add a new OID to be transferred as binary data
    int customTypeOid = 91716;
    // first for receiving
    queryExecutor.addBinaryReceiveOid(91716);
    // check new OID
    assertTrue(queryExecutor.useBinaryForReceive(customTypeOid));
    assertFalse(queryExecutor.useBinaryForSend(customTypeOid));
    // make sure all other OIDs are still there
    for (int oid : oidsReceive) {
      assertTrue(queryExecutor.useBinaryForReceive(oid));
    }
    for (int oid : oidsSend) {
      assertTrue(queryExecutor.useBinaryForSend(oid));
    }
    // then for sending
    queryExecutor.addBinarySendOid(91716);
    // check new OID
    assertTrue(queryExecutor.useBinaryForReceive(customTypeOid));
    assertTrue(queryExecutor.useBinaryForSend(customTypeOid));
    // make sure all other OIDs are still there
    for (int oid : oidsReceive) {
      assertTrue(queryExecutor.useBinaryForReceive(oid));
    }
    for (int oid : oidsSend) {
      assertTrue(queryExecutor.useBinaryForSend(oid));
    }
    TestUtil.closeDB(con);
  }

  /**
   * Make sure custom binary types are handled automatically.
   *
   * @throws SQLException if a database error occurs
   */
  @Test
  public void testCustomBinaryTypes() throws SQLException {
    con = TestUtil.openDB();

    PgConnection pgconn = (PgConnection) con;
    QueryExecutor queryExecutor = pgconn.getQueryExecutor();

    // define an oid of a binary type for testing, POINT is used here as it already exists in the
    // database and requires no complex own type definition
    int customTypeOid = Oid.POINT;
    // the only disadvantage is that it is added automatically in the PgConnection, so be sure to
    // remove type OID from predefined lists
    queryExecutor.getBinaryReceiveOids().remove(customTypeOid);
    queryExecutor.getBinarySendOids().remove(customTypeOid);
    assertFalse(queryExecutor.useBinaryForReceive(customTypeOid));
    assertFalse(queryExecutor.useBinaryForSend(customTypeOid));

    // make sure the test type implements PGBinaryObject
    assertTrue("test type should implement PGBinaryObject",
        PGBinaryObject.class.isAssignableFrom(TestCustomType.class));
    // now define a custom type, which will add it to the binary sent/received OIDs (if the type
    // implements PGBinaryObject)
    pgconn.addDataType("point", TestCustomType.class);
    // check if the type was marked for binary transfer
    assertTrue("type implementing PGBinaryObject should be received binary",
        queryExecutor.useBinaryForReceive(customTypeOid));
    assertTrue("type implementing PGBinaryObject should be sent binary",
        queryExecutor.useBinaryForSend(customTypeOid));

    // INSERT INTO test_binary(id,name,geom) values(1,'Test',Point(1,2))
    try (Statement st = con.createStatement()) {
      // insert some data for testing
      st.execute("INSERT INTO test_binary(id,name,geom) values(1,'Test',Point(1,2))");
      // try to read it
      try (ResultSet rs = st.executeQuery("SELECT geom FROM test_binary WHERE id=1")) {
        assertTrue(rs.next());
        Object o = rs.getObject(1);
        assertNotNull(o);
        // make sure type is correct
        assertEquals(TestCustomType.class, o.getClass());
        TestCustomType co = (TestCustomType) o;
        // first try should not read binary as no prepared statement was used and it was not called
        // multiple times
        assertFalse(
            "first was binary though no prepared statement was used and it was not called multiple times",
            co.wasReadBinary());
      }
    }

    TestCustomType co;
    // now enforce using binary mode
    try (PreparedStatement pst = con.prepareStatement("SELECT geom FROM test_binary WHERE id=?")) {
      pst.setInt(1, 1);
      // execute it 10 times to trigger binary transfer
      for (int i = 0; i < 10; i++) {
        try (ResultSet rs = pst.executeQuery()) {
          assertTrue(rs.next());
        }
      }
      // do the real check
      try (ResultSet rs = pst.executeQuery()) {
        assertTrue(rs.next());
        Object o = rs.getObject(1);
        assertNotNull(o);
        // make sure type is correct
        assertEquals(TestCustomType.class, o.getClass());
        co = (TestCustomType) o;
        // now binary transfer should be working
        assertTrue("reading should be binary as a prepared statement was used multiple times",
            co.wasReadBinary());
      }
    }

    // ensure flag is still unset
    assertFalse(co.wasWrittenBinary());
    // now try to write it
    try (PreparedStatement pst =
        con.prepareStatement("INSERT INTO test_binary(id,geom) VALUES(?,?)")) {
      pst.setInt(1, 2);
      pst.setObject(2, co);
      assertEquals(1, pst.executeUpdate());
      // make sure transfer was binary
      assertTrue(co.wasWrittenBinary());
    }

    TestUtil.closeDB(con);
  }

  /**
   * Custom type that supports binary format.
   */
  @SuppressWarnings("serial")
  public static class TestCustomType extends PGobject implements PGBinaryObject {

    private byte[] byteValue;
    private boolean wasReadBinary;
    private boolean wasWrittenBinary;

    /**
     * Constructs an instance.
     */
    public TestCustomType() {
    }

    @Nullable
    @Override
    public String getValue() {
      // set flag
      this.wasWrittenBinary = false;
      return super.getValue();
    }

    @Override
    public int lengthInBytes() {
      if (byteValue != null) {
        return byteValue.length;
      } else {
        return 0;
      }
    }

    @Override
    public void setByteValue(byte[] value, int offset) throws SQLException {
      this.wasReadBinary = true;
      // remember the byte value
      byteValue = new byte[value.length - offset];
      System.arraycopy(value, offset, byteValue, 0, byteValue.length);
    }

    @Override
    public void setValue(@Nullable String value) throws SQLException {
      super.setValue(value);
      // set flag
      this.wasReadBinary = false;
    }

    @Override
    public void toBytes(byte[] bytes, int offset) {
      if (byteValue != null) {
        // make sure array is large enough
        if ((bytes.length - offset) <= byteValue.length) {
          // copy data
          System.arraycopy(byteValue, 0, bytes, offset, byteValue.length);
        } else {
          throw new IllegalArgumentException(
              "byte array is too small, expected: " + byteValue.length + " got: "
                  + (bytes.length - offset));
        }
      } else {
        throw new IllegalStateException("no geometry has been set");
      }
      // set flag
      this.wasWrittenBinary = true;
    }

    /**
     * Checks, if this type was read in binary mode.
     *
     * @return true for binary mode, else false
     */
    public boolean wasReadBinary() {
      return this.wasReadBinary;
    }

    /**
     * Checks, if this type was written in binary mode.
     *
     * @return true for binary mode, else false
     */
    public boolean wasWrittenBinary() {
      return this.wasWrittenBinary;
    }

  }
}
