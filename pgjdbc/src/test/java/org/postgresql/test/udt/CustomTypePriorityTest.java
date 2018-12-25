/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import org.postgresql.core.BaseConnection;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;

public class CustomTypePriorityTest {

  protected BaseConnection con;

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB().unwrap(BaseConnection.class);
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.closeDB(con);
  }

  @Test
  public void testGetObjectStatement() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("numeric", BigDecimalSQLData.class);
    con.setTypeMap(typeMap);

    Statement stmt = con.createStatement();
    ResultSet result = stmt.executeQuery("SELECT '1.00'::decimal AS testvalue");
    Assert.assertTrue(result.next());
    // Custom mapping should have taken priority here
    BigDecimalSQLData value = (BigDecimalSQLData)result.getObject("testvalue");
    Assert.assertEquals("Will be constant value when created through SQLData implementation", BigDecimalSQLData.CONSTANT_VALUE, value);
    Assert.assertFalse(result.next());
    stmt.close();
  }

  @Test
  public void testGetObjectPreparedStatement() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ? AS normal, ? AS sqldata");
    // If this uses SQLData, it will actually write CONSTANT_VALUE
    pstmt.setObject(1, new BigDecimal("1.00"));
    pstmt.setObject(2, new BigDecimalSQLData("2.00"));
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());

    Assert.assertEquals(new BigDecimal("1.00"), result.getBigDecimal(1));
    Assert.assertEquals(new BigDecimal("2.00"), result.getBigDecimal(2));

    Assert.assertEquals(new BigDecimal("1.00"), result.getObject("normal"));
    Assert.assertEquals("SQLData should have taken priority here", new BigDecimal("2.00"), result.getObject("sqldata"));

    Assert.assertEquals(BigDecimalSQLData.CONSTANT_VALUE, result.getObject(1, Collections.<String, Class<?>>singletonMap("numeric", BigDecimalSQLData.class)));
    Assert.assertEquals(BigDecimalSQLData.CONSTANT_VALUE, result.getObject("sqldata", Collections.<String, Class<?>>singletonMap("numeric", BigDecimalSQLData.class)));

    Assert.assertFalse(result.next());
    pstmt.close();
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test
  public void testGetObjectUDT() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("numeric", BigDecimalSQLData.class);
    con.setTypeMap(typeMap);

    Statement stmt = con.createStatement();
    ResultSet result = stmt.executeQuery("SELECT '1.00'::decimal AS testvalue");
    Assert.assertTrue(result.next());
    // Custom mapping should have taken priority here?
    BigDecimal value = result.getObject("testvalue", BigDecimal.class);
    Assert.assertEquals("Will be constant value when created through SQLData implementation", BigDecimalSQLData.CONSTANT_VALUE, value);
    Assert.assertFalse(result.next());
    stmt.close();
  }
  //#endif
}
