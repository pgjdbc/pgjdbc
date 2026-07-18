/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
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

@ParameterizedClass
@MethodSource("data")
public class BitFieldTest extends BaseTest4 {

  public BitFieldTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  private static class TestData {
    private final String bitValue;
    private final String tableName;
    private final String tableFields;
    private final boolean isVarBit;

    TestData(String bitValue, String tableName, String tableFields, boolean isVarBit) {
      this.bitValue = bitValue;
      this.tableName = tableName;
      this.tableFields = tableFields;
      this.isVarBit = isVarBit;
    }

    public String getBitValue() {
      return bitValue;
    }

    public String getTableName() {
      return tableName;
    }

    public String getTableFields() {
      return tableFields;
    }

    public boolean getIsVarBit() {
      return isVarBit;
    }
  }

  private static final String fieldName = "field_bit";
  public static final String testBitValue = "0101010100101010101010100101";
  private static final TestData[] testBitValues = new TestData[]{
      new TestData("0", "test_bit_field_0a", fieldName + " bit", false),
      new TestData("0", "test_bit_field_0b", fieldName + " bit(1)", false),
      new TestData("1", "test_bit_field_1a", fieldName + " bit", false),
      new TestData("1", "test_bit_field_1b", fieldName + " bit(1)", false),
      new TestData(testBitValue, "test_bit_field_gt1_1", String.format("%s bit(%d)", fieldName,
          testBitValue.length()), false),
      new TestData(testBitValue, "test_varbit_field_gt1_1", String.format("%s varbit(%d)", fieldName,
          testBitValue.length()), true),
      new TestData("1", "test_varbit_field_1", String.format("%s varbit(1)", fieldName), true),
      new TestData("0", "test_varbit_field_0", String.format("%s varbit(1)", fieldName), true)
  };

  @Override
  public void setUp() throws Exception {
    super.setUp();
    try (Statement stmt = con.createStatement()) {
      for (TestData testData : testBitValues) {
        TestUtil.createTempTable(con, testData.getTableName(), testData.getTableFields());
        stmt.execute(String.format("INSERT INTO %s values(b'%s')", testData.getTableName(),
            testData.getBitValue()));
      }
    }
  }

  @Override
  public void tearDown() throws SQLException {
    for (TestData testData : testBitValues) {
      TestUtil.dropTable(con, testData.getTableName());
    }
    super.tearDown();
  }

  @Test
  public void testGetObjectForBitFields() throws SQLException {
    for (TestData testData : testBitValues) {
      try (PreparedStatement pstmt = con.prepareStatement(String.format("SELECT field_bit FROM %s "
          + "limit 1", testData.getTableName()))) {
        checkBitFieldValue(pstmt, testData.getBitValue(), testData.getIsVarBit());
      }
    }
  }

  @Test
  public void testSetBitParameter() throws SQLException {
    for (TestData testData : testBitValues) {
      try (PreparedStatement pstmt = con.prepareStatement(
          String.format("SELECT field_bit FROM %s where ", testData.getTableName())
              + "field_bit = ?")) {
        PGobject param = new PGobject();
        param.setValue(testData.getBitValue());
        param.setType(testData.getIsVarBit() ? "varbit" : "bit");
        pstmt.setObject(1, param);
        checkBitFieldValue(pstmt, testData.getBitValue(), testData.getIsVarBit());
      }
    }
  }

  @Test
  public void testBitArrayReturnsPGobjectArray() throws SQLException {
    try (PreparedStatement pstmt = con.prepareStatement("SELECT ARRAY[b'101', b'010']::bit(3)[]");
         ResultSet rs = pstmt.executeQuery()) {
      assertTrue(rs.next());
      Object array = rs.getArray(1).getArray();
      assertInstanceOf(PGobject[].class, array);
      PGobject[] out = (PGobject[]) array;
      assertEquals(2, out.length);
      assertEquals("101", out[0].getValue());
      assertEquals("010", out[1].getValue());
    }
  }

  @Test
  public void testVarbitArrayReturnsPGobjectArray() throws SQLException {
    try (PreparedStatement pstmt = con.prepareStatement("SELECT ARRAY[b'1', b'0101']::varbit[]");
         ResultSet rs = pstmt.executeQuery()) {
      assertTrue(rs.next());
      Object array = rs.getArray(1).getArray();
      assertInstanceOf(PGobject[].class, array);
      PGobject[] out = (PGobject[]) array;
      assertEquals(2, out.length);
      assertEquals("1", out[0].getValue());
      assertEquals("0101", out[1].getValue());
    }
  }

  private static void checkBitFieldValue(PreparedStatement pstmt, String bitValue, boolean isVarBit) throws SQLException {
    try (ResultSet rs = pstmt.executeQuery()) {
      assertTrue(rs.next());
      Object o = rs.getObject(1);
      if (bitValue.length() == 1 && !isVarBit) {
        assertInstanceOf(Boolean.class, o, "Failed for " + bitValue);
        Boolean b = (Boolean) o;
        assertEquals(bitValue.charAt(0) == '1', b, "Failed for " + bitValue);
      } else {
        assertInstanceOf(PGobject.class, o, "Failed for " + bitValue);
        PGobject pGobject = (PGobject) o;
        assertEquals(bitValue, pGobject.getValue(), "Failed for " + bitValue);
      }
      String s = rs.getString(1);
      assertEquals(bitValue, s);
    }
  }
}
