/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertEquals;

import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Ignore;
import org.junit.Test;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
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
              "CREATE OR REPLACE FUNCTION testspg__getTimestampWithoutTimeZoneWithoutArg() "
                      + "RETURNS timestamp without time zone AS '  "
                      + "begin return TIMESTAMP WITHOUT TIME ZONE ''2004-10-19 10:23:54.000123''; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getTimestampWithTimeZoneWithoutArg() "
                      + "RETURNS timestamp with time zone AS '  "
                      + "begin return TIMESTAMP WITH TIME ZONE ''2004-10-19 10:23:54.000123+02:00''; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getDateWithoutArg() "
                      + "RETURNS date AS '  "
                      + "begin return DATE ''2004-10-19''; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getTimeWithoutTimeZoneWithoutArg() "
                      + "RETURNS time without time zone AS '  "
                      + "begin return TIME WITHOUT TIME ZONE ''10:23:54''; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getTimeWithTimeZoneWithoutArg() "
                      + "RETURNS time with time zone AS '  "
                      + "begin return TIME WITH TIME ZONE ''10:23:54.000123+02''; end; ' LANGUAGE plpgsql;");
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
      stmt.execute("drop FUNCTION testspg__getTimestampWithoutTimeZoneWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getTimestampWithTimeZoneWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getDateWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getTimeWithoutTimeZoneWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getTimeWithTimeZoneWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getResultSetWithoutArg ();");
    }
    super.tearDown();
  }

  @Test
  public void testGetTimestampWithoutTimeZoneWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getTimestampWithoutTimeZoneWithoutArg () }")) {
      call.registerOutParameter(1, Types.TIMESTAMP);
      call.execute();
      assertEquals(LocalDateTime.parse("2004-10-19T10:23:54.000123"), call.getObject(1, LocalDateTime.class));
    }
  }

  @Test
  @Ignore("depends on #695")
  public void testGetTimestampWithTimeZoneWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getTimestampWithTimeZoneWithoutArg () }")) {
      call.registerOutParameter(1, Types.TIMESTAMP_WITH_TIMEZONE);
      call.execute();
      assertEquals(OffsetDateTime.parse("2004-10-19T10:23:54.000123+02:00"), call.getObject(1, OffsetDateTime.class));
    }
  }

  @Test
  public void testGetDateWithoutArgWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getDateWithoutArg () }")) {
      call.registerOutParameter(1, Types.DATE);
      call.execute();
      assertEquals(LocalDate.parse("2004-10-19"), call.getObject(1, LocalDate.class));
    }
  }

  @Test
  public void testGetTimeWithoutTimeZoneWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getTimeWithoutTimeZoneWithoutArg () }")) {
      call.registerOutParameter(1, Types.TIME);
      call.execute();
      assertEquals(LocalTime.parse("10:23:54"), call.getObject(1, LocalTime.class));
    }
  }

  @Test
  public void testGetResultSetWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getResultSetWithoutArg () }")) {
      con.setAutoCommit(false);
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

}
