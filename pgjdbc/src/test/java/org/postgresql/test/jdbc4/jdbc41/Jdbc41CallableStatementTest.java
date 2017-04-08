/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4.jdbc41;

import static org.junit.Assert.assertEquals;

import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.CallableStatement;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Tests for JDBC 4.1 features in {@link org.postgresql.jdbc.PgCallableStatement}.
 */
public class Jdbc41CallableStatementTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();

    try (Statement stmt = con.createStatement();) {
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getNumericWithoutArg() "
                      + "RETURNS numeric AS '  "
                      + "begin return 42; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getIntegerWithoutArg() "
                      + "RETURNS integer AS '  "
                      + "begin return 42; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getBigintWithoutArg() "
                      + "RETURNS bigint AS '  "
                      + "begin return 42; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getRealWithoutArg() "
                      + "RETURNS real AS '  "
                      + "begin return 42.42; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getDoublePrecisionWithoutArg() "
                      + "RETURNS double precision AS '  "
                      + "begin return 42.42; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getVarcharWithoutArg() "
                      + "RETURNS varchar AS '  "
                      + "begin return ''bob''; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getBooleanWithoutArg() "
                      + "RETURNS boolean AS '  "
                      + "begin return true; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getTimestampWithoutTimeZoneWithoutArg() "
                      + "RETURNS timestamp without time zone AS '  "
                      + "begin return TIMESTAMP WITHOUT TIME ZONE ''2004-10-19 10:23:54.000123''; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getTimestampWithTimeZoneWithoutArg() "
                      + "RETURNS timestamp with time zone AS '  "
                      + "begin return TIMESTAMP WITH TIME ZONE ''2004-10-19 10:23:54.000123+02''; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getDateWithoutArg() "
                      + "RETURNS date AS '  "
                      + "begin return DATE ''2004-10-19''; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getTimeWithoutTimeZoneWithoutArg() "
                      + "RETURNS time without time zone AS '  "
                      + "begin return TIME WITHOUT TIME ZONE ''10:23:54.000123''; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getTimeWithTimeZoneWithoutArg() "
                      + "RETURNS time with time zone AS '  "
                      + "begin return TIME WITH TIME ZONE ''10:23:54.000123+02''; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getUuidWithoutArg() "
                      + "RETURNS uuid AS '  "
                      + "begin return ''a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11''; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getInetWithoutArg() "
                      + "RETURNS inet AS '  "
                      + "begin return ''192.168.100.128''; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getXmlWithoutArg() "
                      + "RETURNS xml AS '  "
                      + "begin return XMLPARSE (DOCUMENT ''<?xml version=\"1.0\"?><book><title>Manual</title></book>''); end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getIntegerArrayWithoutArg() "
                      + "RETURNS integer[] AS '  "
                      + "begin return ''{1,2,3}''; end; ' LANGUAGE plpgsql;");
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
      stmt.execute("drop FUNCTION testspg__getNumericWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getIntegerWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getBigintWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getRealWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getDoublePrecisionWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getVarcharWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getBooleanWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getTimestampWithoutTimeZoneWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getTimestampWithTimeZoneWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getDateWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getTimeWithoutTimeZoneWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getTimeWithTimeZoneWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getUuidWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getInetWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getXmlWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getIntegerArrayWithoutArg ();");
    }
    super.tearDown();
  }

  @Test
  public void testGetNumericWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getNumericWithoutArg () }")) {
      call.registerOutParameter(1, Types.NUMERIC);
      call.execute();
      assertEquals(new BigDecimal(42), call.getObject(1, BigDecimal.class));
    }
  }

  @Test
  public void testGetIntegerWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getIntegerWithoutArg () }")) {
      call.registerOutParameter(1, Types.INTEGER);
      call.execute();
      assertEquals(Integer.valueOf(42), call.getObject(1, Integer.class));
    }
  }

  @Test
  public void testGetBigintWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getBigintWithoutArg () }")) {
      call.registerOutParameter(1, Types.BIGINT);
      call.execute();
      assertEquals(Long.valueOf(42), call.getObject(1, Long.class));
    }
  }

  @Test
  public void testGetRealWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getRealWithoutArg () }")) {
      call.registerOutParameter(1, Types.REAL);
      call.execute();
      assertEquals(42.42f, call.getObject(1, Float.class), 0.01f);
    }
  }

  @Test
  public void testGetDoublePrecisionWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getDoublePrecisionWithoutArg () }")) {
      call.registerOutParameter(1, Types.DOUBLE);
      call.execute();
      assertEquals(42.42d, call.getObject(1, Double.class), 0.01d);
    }
  }

  @Test
  public void testGetVarcharWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getVarcharWithoutArg () }")) {
      call.registerOutParameter(1, Types.VARCHAR);
      call.execute();
      assertEquals("bob", call.getObject(1, String.class));
    }
  }

  @Test
  public void testGetBooleanWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getBooleanWithoutArg () }")) {
      call.registerOutParameter(1, Types.BOOLEAN);
      call.execute();
      assertEquals(Boolean.TRUE, call.getObject(1, Boolean.class));
    }
  }

  @Test
  public void testGetTimestampWithoutTimeZoneWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getTimestampWithoutTimeZoneWithoutArg () }")) {
      call.registerOutParameter(1, Types.TIMESTAMP);
      call.execute();
      assertEquals(Timestamp.valueOf("2004-10-19 10:23:54.000123"), call.getObject(1, Timestamp.class));
    }
  }

  @Test
  public void testGetDateWithoutArgWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getDateWithoutArg () }")) {
      call.registerOutParameter(1, Types.DATE);
      call.execute();
      assertEquals(Date.valueOf("2004-10-19"), call.getObject(1, Date.class));
    }
  }

  @Test
  public void testGetTimeWithoutTimeZoneWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getTimeWithoutTimeZoneWithoutArg () }")) {
      call.registerOutParameter(1, Types.TIME);
      call.execute();
      assertEquals(Time.valueOf("10:23:54"), call.getObject(1, Time.class));
    }
  }

  @Test
  public void testGetUuidWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getUuidWithoutArg () }")) {
      call.registerOutParameter(1, Types.OTHER);
      call.execute();
      assertEquals(UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"), call.getObject(1, UUID.class));
    }
  }

  @Test
  public void testGetInetWithoutArg() throws SQLException, UnknownHostException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getInetWithoutArg () }")) {
      call.registerOutParameter(1, Types.OTHER);
      call.execute();
      assertEquals(InetAddress.getByAddress(new byte[] {(byte) 192, (byte) 168, 100, (byte) 128}), call.getObject(1, InetAddress.class));
    }
  }

  @Test
  public void testGetXmlWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getXmlWithoutArg () }")) {
      call.registerOutParameter(1, Types.SQLXML);
      call.execute();
      SQLXML sqlXml = call.getObject(1, SQLXML.class);
      try {
        assertEquals("<book><title>Manual</title></book>", sqlXml.getString());
      } finally {
        sqlXml.free();
      }
    }
  }

  @Test
  public void testGetIntegerArrayWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getIntegerArrayWithoutArg () }")) {
      call.registerOutParameter(1, Types.ARRAY);
      call.execute();
      java.sql.Array array = call.getObject(1, java.sql.Array.class);
      try {
        List<Integer> values = new ArrayList<>(2);
        try (ResultSet rs = array.getResultSet()) {
          while (rs.next()) {
            values.add(rs.getInt(1));
          }
        }
        assertEquals(Arrays.asList(1, 2, 3), values);
      } finally {
        array.free();
      }
    }
  }

}
