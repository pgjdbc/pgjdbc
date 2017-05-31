/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */


package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertEquals;

import org.postgresql.PGStatement;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.GregorianCalendar;
import java.util.TimeZone;


/**
 * Created by davec on 3/24/17.
 */
public class DateTimeTest {
  private Connection con;

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createTable(con, TSWOTZ_TABLE, "ts timestamp without time zone");
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropTable(con, TSWOTZ_TABLE);
    TestUtil.closeDB(con);
  }

  @Test
  public void testInfinity() throws SQLException {
    runInfinityTests(TSWOTZ_TABLE, PGStatement.DATE_POSITIVE_INFINITY, false);
    runInfinityTests(TSWOTZ_TABLE, PGStatement.DATE_NEGATIVE_INFINITY, false);
    runInfinityTests(TSWOTZ_TABLE, PGStatement.DATE_POSITIVE_INFINITY, true);
    runInfinityTests(TSWOTZ_TABLE, PGStatement.DATE_NEGATIVE_INFINITY, true);
  }

  private void runInfinityTests(String table, long value, boolean binary) throws SQLException {
    LocalDateTime expected;
    GregorianCalendar cal = new GregorianCalendar();
    // Pick some random timezone that is hopefully different than ours
    // and exists in this JVM.
    cal.setTimeZone(TimeZone.getTimeZone("Europe/Warsaw"));

    String strValue;
    if (value == PGStatement.DATE_POSITIVE_INFINITY) {
      expected = LocalDateTime.MAX;
      strValue = "infinity";
    } else {
      expected = LocalDateTime.MIN;
      strValue = "-infinity";
    }

    Statement stmt = con.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL(table, "'" + strValue + "'"));
    stmt.close();

    PreparedStatement ps = con.prepareStatement(TestUtil.insertSQL(table, "?"));
    ps.setTimestamp(1, new Timestamp(value));
    ps.executeUpdate();
    ps.setTimestamp(1, new Timestamp(value), cal);
    ps.executeUpdate();
    ps.setObject(1, expected);
    ps.executeUpdate();
    ps.close();

    ps = con.prepareStatement("select ts from " + table);
    if (binary) {
      // cast to the pg extension interface
      org.postgresql.PGStatement pgstmt = ps.unwrap(org.postgresql.PGStatement.class);

      // force binary
      pgstmt.setPrepareThreshold(-1);
    }

    ResultSet rs = ps.executeQuery();
    while (rs.next()) {
      LocalDateTime dateTime = rs.getObject(1, LocalDateTime.class);
      assertEquals(expected,dateTime);
    }
    rs.close();
    ps.close();

    stmt = con.createStatement();
    assertEquals(4, stmt.executeUpdate("DELETE FROM " + table));
    stmt.close();
  }

  private static final String TSWOTZ_TABLE = "testtimestampwotz";

}
