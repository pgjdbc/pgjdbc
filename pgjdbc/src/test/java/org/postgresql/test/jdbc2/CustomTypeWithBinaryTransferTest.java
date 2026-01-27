/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.core.QueryExecutor;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;

/**
 * TestCase to test handling of binary types for custom objects.
 */
@ParameterizedClass
@MethodSource("data")
public class CustomTypeWithBinaryTransferTest extends BaseTest4 {
  // define an oid of a binary type for testing, POINT is used here as it already exists in the
  // database and requires no complex own type definition
  private static final int CUSTOM_TYPE_OID = Oid.POINT;

  public CustomTypeWithBinaryTransferTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  /**
   * Set up the fixture for this testcase: the tables for this test.
   *
   * @throws SQLException if a database error occurs
   */
  @BeforeAll
  public static void createTestTable() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createTable(con, "test_binary_pgobject", "id integer,name text,geom point");
    }
  }

  /**
   * Tear down the fixture for this test case.
   *
   * @throws SQLException if a database error occurs
   */
  @AfterAll
  public static void dropTestTable() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, "test_binary_pgobject");
    }
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    QueryExecutor queryExecutor = con.unwrap(BaseConnection.class).getQueryExecutor();
    queryExecutor.removeBinarySendOid(CUSTOM_TYPE_OID);
    queryExecutor.removeBinaryReceiveOid(CUSTOM_TYPE_OID);
    assertBinaryForReceive(CUSTOM_TYPE_OID, false,
        () -> "Binary transfer for point type should be disabled since we've deactivated it in "
            + "updateProperties");

    assertBinaryForSend(CUSTOM_TYPE_OID, false,
        () -> "Binary transfer for point type should be disabled since we've deactivated it in "
            + "updateProperties");
    try (Statement st = con.createStatement()) {
      st.execute("DELETE FROM test_binary_pgobject");
      st.execute("INSERT INTO test_binary_pgobject(id,name,geom) values(1,'Test',Point(1,2))");
    }
  }

  /**
   * Make sure custom binary types are handled automatically.
   *
   * @throws SQLException if a database error occurs
   */
  @Test
  public void testCustomBinaryTypes() throws SQLException {
    PGConnection pgconn = con.unwrap(PGConnection.class);

    // make sure the test type implements PGBinaryObject
    assertTrue(PGBinaryObject.class.isAssignableFrom(TestCustomType.class), "test type should implement PGBinaryObject");

    // now define a custom type, which will add it to the binary sent/received OIDs (if the type
    // implements PGBinaryObject)
    pgconn.addDataType("point", TestCustomType.class);
    // check if the type was marked for binary transfer
    if (preferQueryMode != PreferQueryMode.SIMPLE) {
      assertBinaryForReceive(CUSTOM_TYPE_OID, true,
          () -> "Binary transfer for point type should be activated by addDataType(..., "
              + "TestCustomType.class)");
      assertBinaryForSend(CUSTOM_TYPE_OID, true,
          () -> "Binary transfer for point type should be activated by addDataType(..., "
              + "TestCustomType.class)");
    }

    TestCustomType co;
    // Try with PreparedStatement
    try (PreparedStatement pst = con.prepareStatement("SELECT geom FROM test_binary_pgobject WHERE id=?")) {
      pst.setInt(1, 1);
      try (ResultSet rs = pst.executeQuery()) {
        assertTrue(rs.next(), "rs.next()");
        Object o = rs.getObject(1);
        co = (TestCustomType) o;
        // now binary transfer should be working
        if (preferQueryMode == PreferQueryMode.SIMPLE) {
          assertEquals("text", co.wasReadBinary() ? "binary" : "text", "reading via prepared statement: TestCustomType.wasReadBinary() should use text encoding since preferQueryMode=SIMPLE");
        } else {
          assertEquals(binaryMode == BinaryMode.FORCE ? "binary" : "text", co.wasReadBinary() ? "binary" : "text", "reading via prepared statement: TestCustomType.wasReadBinary() should use match binary mode requested by the test");
        }
      }
    }

    // ensure flag is still unset
    assertFalse(co.wasWrittenBinary(), "wasWrittenBinary should be false since we have not written the object yet");
    // now try to write it
    try (PreparedStatement pst =
             con.prepareStatement("INSERT INTO test_binary_pgobject(id,geom) VALUES(?,?)")) {
      pst.setInt(1, 2);
      pst.setObject(2, co);
      pst.executeUpdate();
      // make sure transfer was binary
      if (preferQueryMode == PreferQueryMode.SIMPLE) {
        assertEquals(
            "text",
            co.wasWrittenBinary() ? "binary" : "text",
            "writing via prepared statement: TestCustomType.wasWrittenBinary() should use text encoding since preferQueryMode=SIMPLE");
      } else {
        assertEquals(
            binaryMode == BinaryMode.FORCE ? "binary" : "text",
            co.wasWrittenBinary() ? "binary" : "text",
            "writing via prepared statement: TestCustomType.wasWrittenBinary() should use match binary mode requested by the test");
      }
    }
  }

  /**
   * Custom type that supports binary format.
   */
  @SuppressWarnings("serial")
  public static class TestCustomType extends PGobject implements PGBinaryObject {
    private byte @Nullable [] byteValue;
    private boolean wasReadBinary;
    private boolean wasWrittenBinary;

    @Override
    public @Nullable String getValue() {
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
