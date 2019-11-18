/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Assume;
import org.junit.Test;

import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;

public class ParameterMetaDataTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    Assume.assumeTrue("simple protocol only does not support describe statement requests",
        preferQueryMode != PreferQueryMode.SIMPLE);
    TestUtil.createTable(con, "parametertest",
        "a int4, b float8, c text, d point, e timestamp with time zone");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "parametertest");
    super.tearDown();
  }

  @Test
  public void testParameterMD() throws SQLException {
    PreparedStatement pstmt =
        con.prepareStatement("SELECT a FROM parametertest WHERE b = ? AND c = ? AND d >^ ? ");
    ParameterMetaData pmd = pstmt.getParameterMetaData();

    assertEquals(3, pmd.getParameterCount());
    assertEquals(Types.DOUBLE, pmd.getParameterType(1));
    assertEquals("float8", pmd.getParameterTypeName(1));
    assertEquals("java.lang.Double", pmd.getParameterClassName(1));
    assertEquals(Types.VARCHAR, pmd.getParameterType(2));
    assertEquals("text", pmd.getParameterTypeName(2));
    assertEquals("java.lang.String", pmd.getParameterClassName(2));
    assertEquals(Types.OTHER, pmd.getParameterType(3));
    assertEquals("point", pmd.getParameterTypeName(3));
    assertEquals("org.postgresql.geometric.PGpoint", pmd.getParameterClassName(3));

    pstmt.close();
  }

  @Test
  public void testFailsOnBadIndex() throws SQLException {
    PreparedStatement pstmt =
        con.prepareStatement("SELECT a FROM parametertest WHERE b = ? AND c = ?");
    ParameterMetaData pmd = pstmt.getParameterMetaData();
    try {
      pmd.getParameterType(0);
      fail("Can't get parameter for index < 1.");
    } catch (SQLException sqle) {
    }
    try {
      pmd.getParameterType(3);
      fail("Can't get parameter for index 3 with only two parameters.");
    } catch (SQLException sqle) {
    }
  }

  // Make sure we work when mashing two queries into a single statement.
  @Test
  public void testMultiStatement() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "SELECT a FROM parametertest WHERE b = ? AND c = ? ; SELECT b FROM parametertest WHERE a = ?");
    ParameterMetaData pmd = pstmt.getParameterMetaData();

    assertEquals(3, pmd.getParameterCount());
    assertEquals(Types.DOUBLE, pmd.getParameterType(1));
    assertEquals("float8", pmd.getParameterTypeName(1));
    assertEquals(Types.VARCHAR, pmd.getParameterType(2));
    assertEquals("text", pmd.getParameterTypeName(2));
    assertEquals(Types.INTEGER, pmd.getParameterType(3));
    assertEquals("int4", pmd.getParameterTypeName(3));

    pstmt.close();

  }

  // Here we test that we can legally change the resolved type
  // from text to varchar with the complicating factor that there
  // is also an unknown parameter.
  //
  @Test
  public void testTypeChangeWithUnknown() throws SQLException {
    PreparedStatement pstmt =
        con.prepareStatement("SELECT a FROM parametertest WHERE c = ? AND e = ?");
    ParameterMetaData pmd = pstmt.getParameterMetaData();

    pstmt.setString(1, "Hi");
    pstmt.setTimestamp(2, new Timestamp(0L));

    ResultSet rs = pstmt.executeQuery();
    rs.close();
  }

}
