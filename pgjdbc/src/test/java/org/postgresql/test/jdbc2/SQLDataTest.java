/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;

/**
* TestCase to test the reading of a class inheriting SQLData
*/
public class SQLDataTest {
  private static String NAME = "bob";
  private static float FLOATY = 42.3f;
  private static double DOUBLY = 65.9777777777777777777777777777777777;
  private static BigDecimal BIGD = new BigDecimal("78.94444445444");
  private static String BYTES = "some bytes";
  private static String DATE = "2024-10-10";
  private static String TIME = "14:12:35";
  private static String TS = DATE + " " + TIME + ".0";

  private static String wrapQuotes(String text) {
    return "'" + text + "'";
  }

  @BeforeAll
  public static void setUp() throws Exception {
    Connection con = TestUtil.openDB();
    Statement stmt = con.createStatement();

    String columns = String.join(",",
      "id int",
      "idl bigint",
      "ids smallint",
      "name text",
      "valid boolean",
      "bytey numeric",
      "floaty numeric",
      "doubly double precision",
      "bigd numeric",
      "bytes text",
      "datey date",
      "timey time",
      "ts timestamp"
    );
    String values = String.join(",",
      "42",
      "43",
      "44",
      wrapQuotes(NAME),
      "'t'",
      "1",
      String.valueOf(FLOATY),
      String.valueOf(DOUBLY),
      BIGD.toString(),
      wrapQuotes(BYTES),
      wrapQuotes(DATE),
      wrapQuotes(TIME),
      wrapQuotes(TS)
    );
    TestUtil.createTable(con, "sqldatatest", columns);

    stmt.executeUpdate(String.format("INSERT INTO sqldatatest VALUES (%s)", values));
    stmt.executeUpdate("INSERT INTO sqldatatest VALUES (DEFAULT)");

    TestUtil.closeQuietly(stmt);
    TestUtil.closeDB(con);
  }

  @AfterAll
  public static void tearDown() throws Exception {
    Connection con = TestUtil.openDB();
    TestUtil.dropTable(con, "sqldatatest");
    TestUtil.closeDB(con);
  }

  @Test
  public void readSQLData() throws Exception {
    Connection con = TestUtil.openDB();
    Statement stmt = con.createStatement();

    ResultSet rs = stmt.executeQuery("select sqldatatest from sqldatatest");
    assertNotNull(stmt);

    Thing thing;
    rs.next();
    thing = rs.getObject(1, Thing.class);
    assertEquals(42, thing.id);
    assertEquals(43, thing.idl);
    assertEquals(44, thing.ids);
    assertEquals(NAME, thing.name);
    assertTrue(thing.valid);
    assertEquals(1, thing.bytey);
    assertEquals(FLOATY, thing.floaty);
    assertEquals(DOUBLY, thing.doubly);
    assertEquals(BIGD, thing.bigD);
    assertArrayEquals(BYTES.getBytes(), thing.bytes);
    assertEquals(DATE, thing.date.toString());
    assertEquals(TIME, thing.time.toString());
    assertEquals(TS, thing.timestamp.toString());

    rs.next();
    thing = rs.getObject(1, Thing.class);
    assertEquals(0, thing.id);
    assertEquals(0, thing.idl);
    assertEquals(0, thing.ids);
    assertNull(thing.name);
    assertFalse(thing.valid);
    assertEquals(0, thing.bytey);
    assertEquals(0, thing.floaty);
    assertEquals(0, thing.doubly);
    assertNull(thing.bigD);
    assertNull(thing.bytes);
    assertNull(thing.date);
    assertNull(thing.time);
    assertNull(thing.timestamp);

    TestUtil.closeQuietly(stmt);
    TestUtil.closeDB(con);
  }

  public static class Thing implements SQLData {
    public int id;
    public long idl;
    public short ids;
    public String name;
    public boolean valid;
    public byte bytey;
    public float floaty;
    public double doubly;
    public BigDecimal bigD;
    public byte[] bytes;
    public Date date;
    public Time time;
    public Timestamp timestamp;

    @Override
    public String getSQLTypeName() {
      return "sqldatatest";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      id = stream.readInt();
      idl = stream.readLong();
      ids = stream.readShort();
      name = stream.readString();
      valid = stream.readBoolean();
      bytey = stream.readByte();
      floaty = stream.readFloat();
      doubly = stream.readDouble();
      bigD = stream.readBigDecimal();
      bytes = stream.readBytes();
      date = stream.readDate();
      time = stream.readTime();
      timestamp = stream.readTimestamp();
    }

    @Override
    public void writeSQL(SQLOutput stream) {
      // not implemented
    }
  }
}
