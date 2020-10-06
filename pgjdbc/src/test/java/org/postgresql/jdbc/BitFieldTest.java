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

  private static final String bitValue = "1011";

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    con = TestUtil.openDB();
    TestUtil.createTempTable(con, "test_bit_field", String.format("field_bit bit(%d)",
        bitValue.length()));
    Statement stmt = con.createStatement();
    stmt.execute(String.format("INSERT INTO test_bit_field values(b'%s')", bitValue));

    stmt.close();
  }

  @After
  public void tearDown() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("DROP TABLE test_bit_field");
    stmt.close();
    TestUtil.closeDB(con);
  }

  @Test
  public void TestGetObjectForBitField() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("SELECT field_bit  FROM test_bit_field limit 1");
    checkBitFieldValue(pstmt);
  }

  @Test
  public void TestSetBitParameter() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("SELECT field_bit  FROM test_bit_field where field_bit = ?");
    PGobject param = new PGobject();
    param.setValue(bitValue);
    param.setType("bit");
    pstmt.setObject(1, param);
    checkBitFieldValue(pstmt);
  }

  private void checkBitFieldValue(PreparedStatement pstmt) throws SQLException {
    ResultSet rs = pstmt.executeQuery();
    Assert.assertTrue(rs.next());
    Object o = rs.getObject(1);
    Assert.assertTrue(o instanceof PGobject);
    PGobject pGobject = (PGobject) o;
    Assert.assertEquals(bitValue, pGobject.getValue());
    String s = rs.getString(1);
    Assert.assertEquals(bitValue, s);
  }
}
