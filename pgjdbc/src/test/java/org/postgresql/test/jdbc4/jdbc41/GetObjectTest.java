/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4.jdbc41;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGcircle;
import org.postgresql.geometric.PGline;
import org.postgresql.geometric.PGlseg;
import org.postgresql.geometric.PGpath;
import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGpolygon;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGInterval;
import org.postgresql.util.PGmoney;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.UUID;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;

public class GetObjectTest {
  private static final TimeZone UTC = TimeZone.getTimeZone("UTC"); // +0000 always
  private static final TimeZone GMT03 = TimeZone.getTimeZone("GMT+03"); // +0300 always
  private static final TimeZone GMT05 = TimeZone.getTimeZone("GMT-05"); // -0500 always
  private static final TimeZone GMT13 = TimeZone.getTimeZone("GMT+13"); // +1300 always

  private Connection _conn;

  @Before
  public void setUp() throws Exception {
    _conn = TestUtil.openDB();
    TestUtil.createTable(_conn, "table1", "varchar_column varchar(16), "
            + "char_column char(10), "
            + "boolean_column boolean,"
            + "smallint_column smallint,"
            + "integer_column integer,"
            + "bigint_column bigint,"
            + "decimal_column decimal,"
            + "numeric_column numeric,"
            // smallserial requires 9.2 or later
            + (((BaseConnection) _conn).haveMinimumServerVersion(ServerVersion.v9_2) ? "smallserial_column smallserial," : "")
            + "serial_column serial,"
            + "bigserial_column bigserial,"
            + "real_column real,"
            + "double_column double precision,"
            + "timestamp_without_time_zone_column timestamp without time zone,"
            + "timestamp_with_time_zone_column timestamp with time zone,"
            + "date_column date,"
            + "time_without_time_zone_column time without time zone,"
            + "time_with_time_zone_column time with time zone,"
            + "blob_column bytea,"
            + "lob_column oid,"
            + "array_column text[],"
            + "point_column point,"
            + "line_column line,"
            + "lseg_column lseg,"
            + "box_column box,"
            + "path_column path,"
            + "polygon_column polygon,"
            + "circle_column circle,"
            + "money_column money,"
            + "interval_column interval,"
            + (TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v8_3) ? "uuid_column uuid," : "")
            + "inet_column inet,"
            + "cidr_column cidr,"
            + "macaddr_column macaddr"
            + (TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v8_3) ? ",xml_column xml" : "")
    );
  }

  @After
  public void tearDown() throws SQLException {
    TestUtil.dropTable(_conn, "table1");
    TestUtil.closeDB( _conn );
  }

  /**
   * Test the behavior getObject for string columns.
   */
  @Test
  public void testGetString() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","varchar_column,char_column","'varchar_value','char_value'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "varchar_column, char_column"));
    try {
      assertTrue(rs.next());
      assertEquals("varchar_value", rs.getObject("varchar_column", String.class));
      assertEquals("varchar_value", rs.getObject(1, String.class));
      assertEquals("char_value", rs.getObject("char_column", String.class));
      assertEquals("char_value", rs.getObject(2, String.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for string columns.
   */
  @Test
  public void testGetClob() throws SQLException {
    Statement stmt = _conn.createStatement();
    _conn.setAutoCommit(false);
    try {
      char[] data = new char[]{'d', 'e', 'a', 'd', 'b', 'e', 'e', 'f'};
      PreparedStatement insertPS = _conn.prepareStatement(TestUtil.insertSQL("table1", "lob_column", "?"));
      try {
        insertPS.setObject(1, new SerialClob(data), Types.CLOB);
        insertPS.executeUpdate();
      } finally {
        insertPS.close();
      }

      ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "lob_column"));
      try {
        assertTrue(rs.next());
        Clob blob = rs.getObject("lob_column", Clob.class);
        assertEquals(data.length, blob.length());
        assertEquals(new String(data), blob.getSubString(1, data.length));
        blob.free();

        blob = rs.getObject(1, Clob.class);
        assertEquals(data.length, blob.length());
        assertEquals(new String(data), blob.getSubString(1, data.length));
        blob.free();
      } finally {
        rs.close();
      }
    } finally {
      _conn.setAutoCommit(true);
    }
  }

  /**
   * Test the behavior getObject for big decimal columns.
   */
  @Test
  public void testGetBigDecimal() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","decimal_column,numeric_column","0.1,0.1"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "decimal_column, numeric_column"));
    try {
      assertTrue(rs.next());
      assertEquals(new BigDecimal("0.1"), rs.getObject("decimal_column", BigDecimal.class));
      assertEquals(new BigDecimal("0.1"), rs.getObject(1, BigDecimal.class));
      assertEquals(new BigDecimal("0.1"), rs.getObject("numeric_column", BigDecimal.class));
      assertEquals(new BigDecimal("0.1"), rs.getObject(2, BigDecimal.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for timestamp columns.
   */
  @Test
  public void testGetTimestamp() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","timestamp_without_time_zone_column","TIMESTAMP '2004-10-19 10:23:54'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "timestamp_without_time_zone_column"));
    try {
      assertTrue(rs.next());
      Calendar calendar = GregorianCalendar.getInstance();
      calendar.clear();
      calendar.set(Calendar.YEAR, 2004);
      calendar.set(Calendar.MONTH, Calendar.OCTOBER);
      calendar.set(Calendar.DAY_OF_MONTH, 19);
      calendar.set(Calendar.HOUR_OF_DAY, 10);
      calendar.set(Calendar.MINUTE, 23);
      calendar.set(Calendar.SECOND, 54);
      Timestamp expectedNoZone = new Timestamp(calendar.getTimeInMillis());
      assertEquals(expectedNoZone, rs.getObject("timestamp_without_time_zone_column", Timestamp.class));
      assertEquals(expectedNoZone, rs.getObject(1, Timestamp.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for timestamp columns.
   */
  @Test
  public void testGetJavaUtilDate() throws SQLException {
    Statement stmt = _conn.createStatement();
    ResultSet rs = stmt.executeQuery("select TIMESTAMP '2004-10-19 10:23:54'::timestamp as timestamp_without_time_zone_column"
        + ", null::timestamp as null_timestamp");
    try {
      assertTrue(rs.next());
      Calendar calendar = GregorianCalendar.getInstance();
      calendar.clear();
      calendar.set(Calendar.YEAR, 2004);
      calendar.set(Calendar.MONTH, Calendar.OCTOBER);
      calendar.set(Calendar.DAY_OF_MONTH, 19);
      calendar.set(Calendar.HOUR_OF_DAY, 10);
      calendar.set(Calendar.MINUTE, 23);
      calendar.set(Calendar.SECOND, 54);
      java.util.Date expected = new java.util.Date(calendar.getTimeInMillis());
      assertEquals(expected, rs.getObject("timestamp_without_time_zone_column", java.util.Date.class));
      assertEquals(expected, rs.getObject(1, java.util.Date.class));
      assertNull(rs.getObject(2, java.util.Date.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for timestamp columns.
   */
  @Test
  public void testGetTimestampWithTimeZone() throws SQLException {
    runGetTimestampWithTimeZone(UTC, "Z");
    runGetTimestampWithTimeZone(GMT03, "+03:00");
    runGetTimestampWithTimeZone(GMT05, "-05:00");
    runGetTimestampWithTimeZone(GMT13, "+13:00");
  }

  private void runGetTimestampWithTimeZone(TimeZone timeZone, String zoneString) throws SQLException {
    Statement stmt = _conn.createStatement();
    try {
      stmt.executeUpdate(TestUtil.insertSQL("table1","timestamp_with_time_zone_column","TIMESTAMP WITH TIME ZONE '2004-10-19 10:23:54" + zoneString + "'"));

      ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "timestamp_with_time_zone_column"));
      try {
        assertTrue(rs.next());

        Calendar calendar = GregorianCalendar.getInstance(timeZone);
        calendar.clear();
        calendar.set(Calendar.YEAR, 2004);
        calendar.set(Calendar.MONTH, Calendar.OCTOBER);
        calendar.set(Calendar.DAY_OF_MONTH, 19);
        calendar.set(Calendar.HOUR_OF_DAY, 10);
        calendar.set(Calendar.MINUTE, 23);
        calendar.set(Calendar.SECOND, 54);
        Timestamp expectedWithZone = new Timestamp(calendar.getTimeInMillis());
        assertEquals(expectedWithZone, rs.getObject("timestamp_with_time_zone_column", Timestamp.class));
        assertEquals(expectedWithZone, rs.getObject(1, Timestamp.class));
      } finally {
        rs.close();
      }
      stmt.executeUpdate("DELETE FROM table1");
    } finally {
      stmt.close();
    }
  }

  /**
   * Test the behavior getObject for timestamp columns.
   */
  @Test
  public void testGetCalendar() throws SQLException {
    Statement stmt = _conn.createStatement();

    ResultSet rs = stmt.executeQuery("select TIMESTAMP '2004-10-19 10:23:54'::timestamp as timestamp_without_time_zone_column"
        + ", TIMESTAMP '2004-10-19 10:23:54+02'::timestamp as timestamp_with_time_zone_column, null::timestamp as null_timestamp");
    try {
      assertTrue(rs.next());
      Calendar calendar = GregorianCalendar.getInstance();
      calendar.clear();
      calendar.set(Calendar.YEAR, 2004);
      calendar.set(Calendar.MONTH, Calendar.OCTOBER);
      calendar.set(Calendar.DAY_OF_MONTH, 19);
      calendar.set(Calendar.HOUR_OF_DAY, 10);
      calendar.set(Calendar.MINUTE, 23);
      calendar.set(Calendar.SECOND, 54);
      long expected = calendar.getTimeInMillis();
      assertEquals(expected, rs.getObject("timestamp_without_time_zone_column", Calendar.class).getTimeInMillis());
      assertEquals(expected, rs.getObject(1, Calendar.class).getTimeInMillis());
      assertNull(rs.getObject(3, Calendar.class));
      calendar.setTimeZone(TimeZone.getTimeZone("GMT+2:00"));
      expected = calendar.getTimeInMillis();
      assertEquals(expected, rs.getObject("timestamp_with_time_zone_column", Calendar.class).getTimeInMillis());
      assertEquals(expected, rs.getObject(2, Calendar.class).getTimeInMillis());
      assertNull(rs.getObject(3, Calendar.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for date columns.
   */
  @Test
  public void testGetDate() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","date_column","DATE '1999-01-08'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "date_column"));
    try {
      assertTrue(rs.next());
      Calendar calendar = GregorianCalendar.getInstance();
      calendar.clear();
      calendar.set(Calendar.YEAR, 1999);
      calendar.set(Calendar.MONTH, Calendar.JANUARY);
      calendar.set(Calendar.DAY_OF_MONTH, 8);
      Date expectedNoZone = new Date(calendar.getTimeInMillis());
      assertEquals(expectedNoZone, rs.getObject("date_column", Date.class));
      assertEquals(expectedNoZone, rs.getObject(1, Date.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for time columns.
   */
  @Test
  public void testGetTime() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","time_without_time_zone_column","TIME '04:05:06'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "time_without_time_zone_column"));
    try {
      assertTrue(rs.next());
      Calendar calendar = GregorianCalendar.getInstance();
      calendar.clear();
      calendar.set(Calendar.YEAR, 1970);
      calendar.set(Calendar.MONTH, Calendar.JANUARY);
      calendar.set(Calendar.DAY_OF_MONTH, 1);
      calendar.set(Calendar.HOUR, 4);
      calendar.set(Calendar.MINUTE, 5);
      calendar.set(Calendar.SECOND, 6);
      Time expectedNoZone = new Time(calendar.getTimeInMillis());
      assertEquals(expectedNoZone, rs.getObject("time_without_time_zone_column", Time.class));
      assertEquals(expectedNoZone, rs.getObject(1, Time.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for small integer columns.
   */
  @Test
  public void testGetShort() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","smallint_column","1"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "smallint_column"));
    try {
      assertTrue(rs.next());
      assertEquals(Short.valueOf((short) 1), rs.getObject("smallint_column", Short.class));
      assertEquals(Short.valueOf((short) 1), rs.getObject(1, Short.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for small integer columns.
   */
  @Test
  public void testGetShortNull() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","smallint_column","NULL"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "smallint_column"));
    try {
      assertTrue(rs.next());
      assertNull(rs.getObject("smallint_column", Short.class));
      assertNull(rs.getObject(1, Short.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for integer columns.
   */
  @Test
  public void testGetInteger() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","smallint_column, integer_column","1, 2"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "smallint_column, integer_column"));
    try {
      assertTrue(rs.next());
      assertEquals(Integer.valueOf(1), rs.getObject("smallint_column", Integer.class));
      assertEquals(Integer.valueOf(1), rs.getObject(1, Integer.class));
      assertEquals(Integer.valueOf(2), rs.getObject("integer_column", Integer.class));
      assertEquals(Integer.valueOf(2), rs.getObject(2, Integer.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for integer columns.
   */
  @Test
  public void testGetIntegerNull() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","smallint_column, integer_column","NULL, NULL"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "smallint_column, integer_column"));
    try {
      assertTrue(rs.next());
      assertNull(rs.getObject("smallint_column", Integer.class));
      assertNull(rs.getObject(1, Integer.class));
      assertNull(rs.getObject("integer_column", Integer.class));
      assertNull(rs.getObject(2, Integer.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for long columns.
   */
  @Test
  public void testGetBigInteger() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","bigint_column","2147483648"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "bigint_column"));
    try {
      assertTrue(rs.next());
      assertEquals(BigInteger.valueOf(2147483648L), rs.getObject("bigint_column", BigInteger.class));
      assertEquals(BigInteger.valueOf(2147483648L), rs.getObject(1, BigInteger.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for long columns.
   */
  @Test
  public void testGetLong() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","bigint_column","2147483648"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "bigint_column"));
    try {
      assertTrue(rs.next());
      assertEquals(Long.valueOf(2147483648L), rs.getObject("bigint_column", Long.class));
      assertEquals(Long.valueOf(2147483648L), rs.getObject(1, Long.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for long columns.
   */
  @Test
  public void testGetLongNull() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","bigint_column","NULL"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "bigint_column"));
    try {
      assertTrue(rs.next());
      assertNull(rs.getObject("bigint_column", Long.class));
      assertNull(rs.getObject(1, Long.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for double columns.
   */
  @Test
  public void testGetDouble() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","double_column","1.0"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "double_column"));
    try {
      assertTrue(rs.next());
      assertEquals(Double.valueOf(1.0d), rs.getObject("double_column", Double.class));
      assertEquals(Double.valueOf(1.0d), rs.getObject(1, Double.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for double columns.
   */
  @Test
  public void testGetDoubleNull() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","double_column","NULL"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "double_column"));
    try {
      assertTrue(rs.next());
      assertNull(rs.getObject("double_column", Double.class));
      assertNull(rs.getObject(1, Double.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for float columns.
   */
  @Test
  public void testGetFloat() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","real_column","1.0"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "real_column"));
    try {
      assertTrue(rs.next());
      assertEquals(Float.valueOf(1.0f), rs.getObject("real_column", Float.class));
      assertEquals(Float.valueOf(1.0f), rs.getObject(1, Float.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for float columns.
   */
  @Test
  public void testGetFloatNull() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","real_column","NULL"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "real_column"));
    try {
      assertTrue(rs.next());
      assertNull(rs.getObject("real_column", Float.class));
      assertNull(rs.getObject(1, Float.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for serial columns.
   */
  @Test
  public void testGetSerial() throws SQLException {
    if (!((BaseConnection) _conn).haveMinimumServerVersion(ServerVersion.v9_2)) {
      // smallserial requires 9.2 or later
      return;
    }
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","smallserial_column, serial_column","1, 2"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "smallserial_column, serial_column"));
    try {
      assertTrue(rs.next());
      assertEquals(Integer.valueOf(1), rs.getObject("smallserial_column", Integer.class));
      assertEquals(Integer.valueOf(1), rs.getObject(1, Integer.class));
      assertEquals(Integer.valueOf(2), rs.getObject("serial_column", Integer.class));
      assertEquals(Integer.valueOf(2), rs.getObject(2, Integer.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for boolean columns.
   */
  @Test
  public void testGetBoolean() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","boolean_column","TRUE"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "boolean_column"));
    try {
      assertTrue(rs.next());
      assertTrue(rs.getObject("boolean_column", Boolean.class));
      assertTrue(rs.getObject(1, Boolean.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for boolean columns.
   */
  @Test
  public void testGetBooleanNull() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","boolean_column","NULL"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "boolean_column"));
    try {
      assertTrue(rs.next());
      assertNull(rs.getObject("boolean_column", Boolean.class));
      assertNull(rs.getObject(1, Boolean.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for xml columns.
   */
  @Test
  public void testGetBlob() throws SQLException {
    Statement stmt = _conn.createStatement();
    _conn.setAutoCommit(false);
    try {
      byte[] data = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
      PreparedStatement insertPS = _conn.prepareStatement(TestUtil.insertSQL("table1", "lob_column", "?"));
      try {
        insertPS.setObject(1, new SerialBlob(data), Types.BLOB);
        insertPS.executeUpdate();
      } finally {
        insertPS.close();
      }

      ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "lob_column"));
      try {
        assertTrue(rs.next());
        Blob blob = rs.getObject("lob_column", Blob.class);
        assertEquals(data.length, blob.length());
        assertArrayEquals(data, blob.getBytes(1, data.length));
        blob.free();

        blob = rs.getObject(1, Blob.class);
        assertEquals(data.length, blob.length());
        assertArrayEquals(data, blob.getBytes(1, data.length));
        blob.free();
      } finally {
        rs.close();
      }
    } finally {
      _conn.setAutoCommit(true);
    }
  }

  /**
   * Test the behavior getObject for array columns.
   */
  @Test
  public void testGetArray() throws SQLException {
    Statement stmt = _conn.createStatement();
    String[] data = new String[]{"java", "jdbc"};
    stmt.executeUpdate(TestUtil.insertSQL("table1","array_column","'{\"java\", \"jdbc\"}'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "array_column"));
    try {
      assertTrue(rs.next());
      Array array = rs.getObject("array_column", Array.class);
      assertArrayEquals(data, (String[]) array.getArray());
      array.free();

      array = rs.getObject(1, Array.class);
      assertArrayEquals(data, (String[]) array.getArray());
      array.free();
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for xml columns.
   */
  @Test
  public void testGetXml() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v8_3)) {
      // XML column requires PostgreSQL 8.3+
      return;
    }
    Statement stmt = _conn.createStatement();
    String content = "<book><title>Manual</title></book>";
    stmt.executeUpdate(TestUtil.insertSQL("table1","xml_column","XMLPARSE (DOCUMENT '<?xml version=\"1.0\"?><book><title>Manual</title></book>')"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "xml_column"));
    try {
      assertTrue(rs.next());
      SQLXML sqlXml = rs.getObject("xml_column", SQLXML.class);
      assertEquals(content, sqlXml.getString());
      sqlXml.free();

      sqlXml = rs.getObject(1, SQLXML.class);
      assertEquals(content, sqlXml.getString());
      sqlXml.free();
    } finally {
      rs.close();
    }
  }

  /**
   * <p>Test the behavior getObject for money columns.</p>
   *
   * <p>The test is ignored as it is locale-dependent.</p>
   */
  @Ignore
  @Test
  public void testGetMoney() throws SQLException {
    Statement stmt = _conn.createStatement();
    String expected = "12.34";
    stmt.executeUpdate(TestUtil.insertSQL("table1","money_column","'12.34'::float8::numeric::money"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "money_column"));
    try {
      assertTrue(rs.next());
      PGmoney money = rs.getObject("money_column", PGmoney.class);
      assertTrue(money.getValue().endsWith(expected));

      money = rs.getObject(1, PGmoney.class);
      assertTrue(money.getValue().endsWith(expected));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for point columns.
   */
  @Test
  public void testGetPoint() throws SQLException {
    Statement stmt = _conn.createStatement();
    PGpoint expected = new PGpoint(1.0d, 2.0d);
    stmt.executeUpdate(TestUtil.insertSQL("table1","point_column","point '(1, 2)'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "point_column"));
    try {
      assertTrue(rs.next());
      assertEquals(expected, rs.getObject("point_column", PGpoint.class));
      assertEquals(expected, rs.getObject(1, PGpoint.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for line columns.
   */
  @Test
  public void testGetLine() throws SQLException {
    if (!((BaseConnection) _conn).haveMinimumServerVersion(ServerVersion.v9_4)) {
      // only 9.4 and later ship with full line support by default
      return;
    }

    Statement stmt = _conn.createStatement();
    PGline expected = new PGline(1.0d, 2.0d, 3.0d);
    stmt.executeUpdate(TestUtil.insertSQL("table1","line_column","line '{1, 2, 3}'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "line_column"));
    try {
      assertTrue(rs.next());
      assertEquals(expected, rs.getObject("line_column", PGline.class));
      assertEquals(expected, rs.getObject(1, PGline.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for lseg columns.
   */
  @Test
  public void testGetLineseg() throws SQLException {
    Statement stmt = _conn.createStatement();
    PGlseg expected = new PGlseg(1.0d, 2.0d, 3.0d, 4.0d);
    stmt.executeUpdate(TestUtil.insertSQL("table1","lseg_column","lseg '[(1, 2), (3, 4)]'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "lseg_column"));
    try {
      assertTrue(rs.next());
      assertEquals(expected, rs.getObject("lseg_column", PGlseg.class));
      assertEquals(expected, rs.getObject(1, PGlseg.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for box columns.
   */
  @Test
  public void testGetBox() throws SQLException {
    Statement stmt = _conn.createStatement();
    PGbox expected = new PGbox(1.0d, 2.0d, 3.0d, 4.0d);
    stmt.executeUpdate(TestUtil.insertSQL("table1","box_column","box '((1, 2), (3, 4))'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "box_column"));
    try {
      assertTrue(rs.next());
      assertEquals(expected, rs.getObject("box_column", PGbox.class));
      assertEquals(expected, rs.getObject(1, PGbox.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for path columns.
   */
  @Test
  public void testGetPath() throws SQLException {
    Statement stmt = _conn.createStatement();
    PGpath expected = new PGpath(new PGpoint[]{new PGpoint(1.0d, 2.0d), new PGpoint(3.0d, 4.0d)}, true);
    stmt.executeUpdate(TestUtil.insertSQL("table1","path_column","path '[(1, 2), (3, 4)]'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "path_column"));
    try {
      assertTrue(rs.next());
      assertEquals(expected, rs.getObject("path_column", PGpath.class));
      assertEquals(expected, rs.getObject(1, PGpath.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for polygon columns.
   */
  @Test
  public void testGetPolygon() throws SQLException {
    Statement stmt = _conn.createStatement();
    PGpolygon expected = new PGpolygon(new PGpoint[]{new PGpoint(1.0d, 2.0d), new PGpoint(3.0d, 4.0d)});
    stmt.executeUpdate(TestUtil.insertSQL("table1","polygon_column","polygon '((1, 2), (3, 4))'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "polygon_column"));
    try {
      assertTrue(rs.next());
      assertEquals(expected, rs.getObject("polygon_column", PGpolygon.class));
      assertEquals(expected, rs.getObject(1, PGpolygon.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for circle columns.
   */
  @Test
  public void testGetCircle() throws SQLException {
    Statement stmt = _conn.createStatement();
    PGcircle expected = new PGcircle(1.0d, 2.0d, 3.0d);
    stmt.executeUpdate(TestUtil.insertSQL("table1","circle_column","circle '<(1, 2), 3>'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "circle_column"));
    try {
      assertTrue(rs.next());
      assertEquals(expected, rs.getObject("circle_column", PGcircle.class));
      assertEquals(expected, rs.getObject(1, PGcircle.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for interval columns.
   */
  @Test
  public void testGetInterval() throws SQLException {
    Statement stmt = _conn.createStatement();
    PGInterval expected = new PGInterval(0, 0, 3, 4, 5, 6.0d);
    stmt.executeUpdate(TestUtil.insertSQL("table1","interval_column","interval '3 4:05:06'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "interval_column"));
    try {
      assertTrue(rs.next());
      assertEquals(expected, rs.getObject("interval_column", PGInterval.class));
      assertEquals(expected, rs.getObject(1, PGInterval.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for uuid columns.
   */
  @Test
  public void testGetUuid() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v8_3)) {
      // UUID requires PostgreSQL 8.3+
      return;
    }
    Statement stmt = _conn.createStatement();
    String expected = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11";
    stmt.executeUpdate(TestUtil.insertSQL("table1","uuid_column","'" + expected + "'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "uuid_column"));
    try {
      assertTrue(rs.next());
      assertEquals(UUID.fromString(expected), rs.getObject("uuid_column", UUID.class));
      assertEquals(UUID.fromString(expected), rs.getObject(1, UUID.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for inet columns.
   */
  @Test
  public void testGetInetAddressNull() throws SQLException, UnknownHostException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","inet_column","NULL"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "inet_column"));
    try {
      assertTrue(rs.next());
      assertNull(rs.getObject("inet_column", InetAddress.class));
      assertNull(rs.getObject(1, InetAddress.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for inet columns.
   */
  @Test
  public void testGetInet4Address() throws SQLException, UnknownHostException {
    Statement stmt = _conn.createStatement();
    String expected = "192.168.100.128";
    stmt.executeUpdate(TestUtil.insertSQL("table1","inet_column","'" + expected + "'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "inet_column"));
    try {
      assertTrue(rs.next());
      assertEquals(InetAddress.getByName(expected), rs.getObject("inet_column", InetAddress.class));
      assertEquals(InetAddress.getByName(expected), rs.getObject(1, InetAddress.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for inet columns.
   */
  @Test
  public void testGetInet6Address() throws SQLException, UnknownHostException {
    Statement stmt = _conn.createStatement();
    String expected = "2001:4f8:3:ba:2e0:81ff:fe22:d1f1";
    stmt.executeUpdate(TestUtil.insertSQL("table1","inet_column","'" + expected + "'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "inet_column"));
    try {
      assertTrue(rs.next());
      assertEquals(InetAddress.getByName(expected), rs.getObject("inet_column", InetAddress.class));
      assertEquals(InetAddress.getByName(expected), rs.getObject(1, InetAddress.class));
    } finally {
      rs.close();
    }
  }

}
