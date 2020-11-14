/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PGobject;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class BitFieldTest extends BaseTest4 {

  private static class TestData {
    private final String bitValue;
    private final String tableName;
    private final String tableFields;

    TestData(String bitValue, String tableName, String tableFields) {
      this.bitValue = bitValue;
      this.tableName = tableName;
      this.tableFields = tableFields;
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
  }

  private static final String fieldName = "field_bit";
  public static final String testBitValue = "0101010100101010101010100101";
  private static final TestData[] testBitValues = new TestData[]{
      new TestData("0", "test_bit_field_0a", fieldName + " bit"),
      new TestData("0", "test_bit_field_0b", fieldName + " bit(1)"),
      new TestData("1", "test_bit_field_1a", fieldName + " bit"),
      new TestData("1", "test_bit_field_1b", fieldName + " bit(1)"),
      new TestData(testBitValue, "test_bit_field_gt1_1", String.format("%s bit(%d)", fieldName,
          testBitValue.length()))
  };

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    con = TestUtil.openDB();
    Statement stmt = con.createStatement();
    for (TestData testData : testBitValues) {
      TestUtil.createTempTable(con, testData.getTableName(), testData.getTableFields());
      stmt.execute(String.format("INSERT INTO %s values(b'%s')", testData.getTableName(),
          testData.getBitValue()));
    }
  }

  @After
  public void tearDown() throws SQLException {
    Statement stmt = con.createStatement();
    for (TestData testData : testBitValues) {
      stmt.execute(String.format("DROP TABLE %s", testData.getTableName()));
    }
    stmt.close();
    TestUtil.closeDB(con);
  }

  @Test
  public void TestGetObjectForBitFields() throws SQLException {
    // Start from 1 to skip the first testBit value
    for (TestData testData : testBitValues) {
      PreparedStatement pstmt = con.prepareStatement(String.format("SELECT field_bit FROM %s "
          + "limit 1", testData.getTableName()));
      checkBitFieldValue(pstmt, testData.getBitValue());
      pstmt.close();
    }
  }

  @Test
  public void TestSetBitParameter() throws SQLException {
    for (TestData testData : testBitValues) {
      PreparedStatement pstmt = con.prepareStatement(
          String.format("SELECT field_bit FROM %s where ", testData.getTableName())
              + "field_bit = ?");
      PGobject param = new PGobject();
      param.setValue(testData.getBitValue());
      param.setType("bit");
      pstmt.setObject(1, param);
      checkBitFieldValue(pstmt, testData.getBitValue());
      pstmt.close();
    }
  }

  private void checkBitFieldValue(PreparedStatement pstmt, String bitValue) throws SQLException {
    ResultSet rs = pstmt.executeQuery();
    Assert.assertTrue(rs.next());
    Object o = rs.getObject(1);
    if (bitValue.length() == 1) {
      Assert.assertTrue("Failed for " + bitValue, o instanceof java.lang.Boolean);
      Boolean b = (Boolean) o;
      Assert.assertEquals("Failed for " + bitValue, bitValue.charAt(0) == '1', b);
    } else {
      Assert.assertTrue("Failed for " + bitValue, o instanceof PGobject);
      PGobject pGobject = (PGobject) o;
      Assert.assertEquals("Failed for " + bitValue, bitValue, pGobject.getValue());
    }
    String s = rs.getString(1);
    Assert.assertEquals(bitValue, s);
  }
}
