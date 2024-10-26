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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Array;
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
import java.util.HashMap;
import java.util.Map;

/**
* TestCase to test the reading of a class inheriting SQLData
*/
public class SQLDataTest {
  private static final String TABLE_THING = "thing";
  private static final String TABLE_TEST = "test";
  private static final String UDT_THING = "udt_thing";
  private static final String UDT_THING_COL = "udt_thing_col";

  private static final String NAME = "Thing";
  private static final float FLOATY = 42.3f;
  private static final double DOUBLY = 65.9777777777777777777777777777777777;
  private static final BigDecimal BIGD = new BigDecimal("78.94444445444");
  private static final String BYTES = "some bytes";
  private static final String DATE = "2024-10-10";
  private static final String TIME = "14:12:35";
  private static final String TS = DATE + " " + TIME + ".0";

  private Connection con;
  private Statement stmt;

  private static String wrapQuotes(String text) {
    return "'" + text + "'";
  }

  @BeforeAll
  public static void setUp() throws Exception {
    Connection con = TestUtil.openDB();
    Statement stmt = con.createStatement();

    String thingColumns = String.join(",",
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
    String thingValues = String.join(",",
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
    TestUtil.createTable(con, TABLE_THING, thingColumns);

    stmt.executeUpdate(String.format("INSERT INTO %s VALUES (%s)", TABLE_THING, thingValues));
    stmt.executeUpdate(String.format("INSERT INTO %s VALUES (DEFAULT)", TABLE_THING));

    TestUtil.createTable(con, TABLE_TEST, "id int, thingid int");
    stmt.executeUpdate(String.format("INSERT INTO %s VALUES (1, 42)", TABLE_TEST));

    TestUtil.createCompositeType(con, UDT_THING, String.format("id int, thing %s", TABLE_THING));
    TestUtil.createCompositeType(con, UDT_THING_COL, String.format("name text, things %s[]", TABLE_THING));

    TestUtil.closeQuietly(stmt);
    TestUtil.closeDB(con);
  }

  @AfterAll
  public static void tearDown() throws Exception {
    Connection con = TestUtil.openDB();
    TestUtil.dropTable(con, TABLE_THING);
    TestUtil.dropTable(con, TABLE_TEST);
    TestUtil.dropType(con, UDT_THING);
    TestUtil.dropType(con, UDT_THING_COL);
    TestUtil.closeDB(con);
  }

  @BeforeEach
  private void beforeEach() throws Exception {
    con = TestUtil.openDB();
    stmt = con.createStatement();
  }

  @AfterEach
  private void afterEach() throws Exception {
    TestUtil.closeQuietly(stmt);
    TestUtil.closeDB(con);
  }

  private void checkThing(Thing thing) {
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
  }

  private void checkNullThing(Thing thing) {
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
  }

  @Test
  public void readThings() throws Exception {
    ResultSet rs = stmt.executeQuery(String.format("select %s from %s", TABLE_THING, TABLE_THING));

    assertTrue(rs.next());
    checkThing(rs.getObject(1, Thing.class));

    assertTrue(rs.next());
    checkNullThing(rs.getObject(1, Thing.class));
  }

  @Test
  public void readRecursiveThing() throws Exception {
    String sql = String.format("select (%s.id, %s)::%s from %s inner join %s on (thingid = %s.id)",
                               TABLE_TEST, TABLE_THING, UDT_THING, TABLE_TEST, TABLE_THING, TABLE_THING);
    // System.out.println(sql);
    ResultSet rs = stmt.executeQuery(sql);

    assertTrue(rs.next());
    TestObj test = rs.getObject(1, TestObj.class);
    assertEquals(1, test.id);
    checkThing(test.thing);
  }

  private void checkThings(Thing[] things) {
    assertNotNull(things);
    assertEquals(2, things.length);

    for (Thing thing : things) {
      if (thing.id == 0) {
        checkNullThing(thing);
      } else {
        checkThing(thing);
      }
    }
  }

  static Thing[] toThingArray(Array array) throws SQLException {
    Map<String, Class<?>> map = new HashMap<>();
    map.put(TABLE_THING, Thing.class);

    Object[] objects = (Object[])array.getArray(map);

    Thing[] things = new Thing[objects.length];
    for (int ii = 0; ii < objects.length; ii++) {
      things[ii] = (Thing) objects[ii];
    }
    return things;
  }

  @Test
  public void readArray() throws Exception {
    String sql = String.format("select array_agg(%s) from %s", TABLE_THING, TABLE_THING);
    ResultSet rs = stmt.executeQuery(sql);

    assertTrue(rs.next());
    checkThings(toThingArray(rs.getArray(1)));
  }

  // //
  // // This test does not work until we can implemnt Arrays correctly.
  // //
  // @Test
  // public void readArraySQLInput() throws Exception {
  //   String sql = String.format("select ('mythings', array_agg(%s))::%s from %s", TABLE_THING, UDT_THING_COL, TABLE_THING);
  //   ResultSet rs = stmt.executeQuery(sql);

  //   assertTrue(rs.next());

  //   ThingArray array = rs.getObject(1, ThingArray.class);
  //   checkThings(array.things);
  // }

  // /**
  //  * Tests the (type.isArray()) section in PgSQLInput.getConverter()
  //  */
  // @Test
  // public void readArrayTypeSQLInput() throws Exception {
  //   String sql = String.format("select ('mythings', array_agg(%s))::%s from %s", TABLE_THING, UDT_THING_COL, TABLE_THING);
  //   System.out.println(sql);
  //   ResultSet rs = stmt.executeQuery(sql);

  //   assertTrue(rs.next());

  //   // Type type = new TypeToken<Collection<Thing>>(){}.getType();  // Using TypeToken for type information

  //   ThingCollection coll = rs.getObject(1, ThingCollection.class);

  //   checkThings(coll.things);
  // }

  // /**
  //  * Tests the (type.isArray()) section in PgResultSet.getObject()
  //  */
  // @Test
  // public void readArrayType() throws Exception {
  //   String sql = String.format("select array_agg(%s) from %s", TABLE_THING, TABLE_THING);
  //   ResultSet rs = stmt.executeQuery(sql);

  //   assertTrue(rs.next());

  //   checkThings(rs.getObject(1, Thing[].class));
  // }

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
      return TABLE_THING;
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

  public static class TestObj implements SQLData {
    public int id;
    public Thing thing;

    @Override
    public String getSQLTypeName() {
      return UDT_THING;
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      id = stream.readInt();
      thing = stream.readObject(Thing.class);
    }

    @Override
    public void writeSQL(SQLOutput stream) {
      // not implemented
    }
  }

  public static class ThingArray implements SQLData {
    public String name;
    public Thing[] things;

    @Override
    public String getSQLTypeName() {
      return UDT_THING_COL;
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      name = stream.readString();
      System.out.println(name);
      things = SQLDataTest.toThingArray(stream.readArray());
    }

    @Override
    public void writeSQL(SQLOutput stream) {
      // not implemented
    }
  }

  public static class ThingCollection implements SQLData {
    public String name;
    public Thing[] things;

    @Override
    public String getSQLTypeName() {
      return UDT_THING_COL;
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      name = stream.readString();
      things = stream.readObject(Thing[].class);
    }

    @Override
    public void writeSQL(SQLOutput stream) {
      // not implemented
    }
  }
}
