/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tests for JDBC 4.2 features in {@link org.postgresql.jdbc.PgCallableStatement}.
 */
public class Jdbc42CallableStatementTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();

    try (Statement stmt = con.createStatement();) {
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getResultSetWithoutArg() "
                      + "RETURNS refcursor AS '  "
                      + "declare ref refcursor;"
                      + "begin OPEN ref FOR SELECT 1; RETURN ref; end; ' LANGUAGE plpgsql;");
    }
  }

  final String func = "{ ? = call ";
  final String pkgName = "testspg__";

  @Override
  public void tearDown() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("drop FUNCTION testspg__getResultSetWithoutArg ();");
    }
    super.tearDown();
  }

  @Test
  public void testGetResultSetWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getResultSetWithoutArg () }")) {
      con.setAutoCommit(false); // ref cursors only work if auto commit is off
      call.registerOutParameter(1, Types.REF_CURSOR);
      call.execute();
      List<Integer> values = new ArrayList<>(1);
      try (ResultSet rs = call.getObject(1, ResultSet.class)) {
        while (rs.next()) {
          values.add(rs.getInt(1));
        }
      }
      assertEquals(Collections.singletonList(1), values);
    } finally {
      con.setAutoCommit(true);
    }
  }

  @Test
  public void testGetResultSetWithoutArgUnsupportedConversion() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getResultSetWithoutArg () }")) {
      con.setAutoCommit(false); // ref cursors only work if auto commit is off
      call.registerOutParameter(1, Types.REF_CURSOR);
      call.execute();
      try {
        // this should never be allowed even if more types will be implemented in the future
        call.getObject(1, ResultSetMetaData.class);
        fail("conversion from ResultSet to ResultSetMetaData should not be supported");
      } catch (SQLException e) {
        // should reach
      }
    } finally {
      con.setAutoCommit(true);
    }
  }

  @Test
  public void testRegisterOutParameter() throws SQLException {

    CallableStatement cs = null;

    cs = con.prepareCall("{ ? = call xxxx.yyyy (?,?,?,?)}");
    cs.registerOutParameter(1, Types.REF_CURSOR);

    cs.setLong(2, 1000L);
    cs.setLong(3, 500);
    cs.setLong(4, 3000);
    cs.setNull(5, Types.NUMERIC);
  }
}
