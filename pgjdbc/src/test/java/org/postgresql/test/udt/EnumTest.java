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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;

// TODO: Separate tests for SQLData over enum type
// TODO: Separate tests for Domain of enum type
public class EnumTest {

  protected static final String SCHEMA = "\"org.postgresql\"";
  protected static final String DAY_OF_WEEK_TYPE = SCHEMA + ".\"DayOfWeek\"";

  protected enum DayOfWeek {
    sunday("Sunday"),
    monday("Monday"),
    tuesday("Tuesday"),
    wednesday("Wednesday"),
    thursday("Thursday"),
    friday("Friday"),
    saturday("Saturday");

    private final String toString;

    DayOfWeek(String toString) {
      this.toString = toString;
    }

    @Override
    public String toString() {
      return toString;
    }
  }

  protected BaseConnection con;

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB().unwrap(BaseConnection.class);
    TestUtil.createSchema(con, SCHEMA);
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put(DAY_OF_WEEK_TYPE, DayOfWeek.class);
    con.setTypeMap(typeMap);

    StringBuilder values = new StringBuilder();
    for (DayOfWeek dow : DayOfWeek.values()) {
      if (values.length() != 0) {
        values.append(", ");
      }
      values.append('\'').append(dow.name()).append('\'');
    }
    TestUtil.createEnumType(con, DAY_OF_WEEK_TYPE, values.toString());
    TestUtil.createTable(con, "testdow", "dow " + DAY_OF_WEEK_TYPE);
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.closeDB(con);

    con = TestUtil.openDB().unwrap(BaseConnection.class);

    TestUtil.dropTable(con, "testdow");
    TestUtil.dropType(con, DAY_OF_WEEK_TYPE);
    TestUtil.dropSchema(con, SCHEMA);

    TestUtil.closeDB(con);
  }

  @Test
  public void testGetStringFromString() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::" + DAY_OF_WEEK_TYPE + " AS \"Yes\"");
    pstmt.setString(1, DayOfWeek.friday.name());
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    Assert.assertEquals(DayOfWeek.friday.name(), result.getString(1));
    Assert.assertEquals(DayOfWeek.friday.name(), result.getString("Yes"));
    Assert.assertFalse(result.next());
    pstmt.close();
  }

  @Test
  public void testGetStringFromObject() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ? AS \"Yes\"");
    pstmt.setObject(1, DayOfWeek.friday);
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    Assert.assertEquals(DayOfWeek.friday.name(), result.getString(1));
    Assert.assertEquals(DayOfWeek.friday.name(), result.getString("Yes"));
    Assert.assertFalse(result.next());
    pstmt.close();
  }

  @Test
  public void testGetObjectFromString() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::" + DAY_OF_WEEK_TYPE + " AS \"Yes\"");
    pstmt.setString(1, DayOfWeek.friday.name());
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    Assert.assertSame(DayOfWeek.friday, result.getObject(1));
    Assert.assertSame(DayOfWeek.friday, result.getObject("Yes"));
    Assert.assertFalse(result.next());
    pstmt.close();
  }

  @Test
  public void testGetObjectFromObject() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ? AS \"Yes\"");
    pstmt.setObject(1, DayOfWeek.friday);
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    Assert.assertSame(DayOfWeek.friday, result.getObject(1));
    Assert.assertSame(DayOfWeek.friday, result.getObject("Yes"));
    Assert.assertFalse(result.next());
    pstmt.close();
  }

  @Test
  public void testGetObjectWithClassFromString() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::" + DAY_OF_WEEK_TYPE + " AS \"Yes\"");
    pstmt.setString(1, DayOfWeek.friday.name());
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    Assert.assertSame(DayOfWeek.friday, result.getObject(1, DayOfWeek.class));
    Assert.assertSame(DayOfWeek.friday, result.getObject("Yes", DayOfWeek.class));
    Assert.assertFalse(result.next());
    pstmt.close();
  }

  @Test
  public void testGetObjectWithClassFromObject() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ? AS \"Yes\"");
    pstmt.setObject(1, DayOfWeek.friday);
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    Assert.assertSame(DayOfWeek.friday, result.getObject(1, DayOfWeek.class));
    Assert.assertSame(DayOfWeek.friday, result.getObject("Yes", DayOfWeek.class));
    Assert.assertFalse(result.next());
    pstmt.close();
  }

  @Test(expected = SQLException.class)
  public void testGetObjectFromStringOverrideToNoMapping() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::" + DAY_OF_WEEK_TYPE + " AS \"Yes\"");
    pstmt.setString(1, DayOfWeek.friday.name());
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    result.getObject(1, Collections.<String, Class<?>>emptyMap());
    Assert.fail();
  }

  @Test
  public void testGetObjectFromStringOverrideToHaveMapping() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    con.setTypeMap(Collections.<String, Class<?>>emptyMap());

    PreparedStatement pstmt = con.prepareStatement("SELECT ?::" + DAY_OF_WEEK_TYPE + " AS \"Yes\"");
    pstmt.setString(1, DayOfWeek.friday.name());
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    Assert.assertSame(DayOfWeek.friday, result.getObject(1, typeMap));
    Assert.assertSame(DayOfWeek.friday, result.getObject("Yes", typeMap));
    Assert.assertFalse(result.next());
    pstmt.close();
  }

  @Test(expected = SQLException.class)
  public void testGetObjectFromStringNoMapping() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.clear();;
    con.setTypeMap(typeMap);

    PreparedStatement pstmt = con.prepareStatement("SELECT ?::" + DAY_OF_WEEK_TYPE + " AS \"Yes\"");
    pstmt.setString(1, DayOfWeek.friday.name());
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    result.getObject(1);
    Assert.fail();
  }

  @Test(expected = SQLException.class)
  public void testGetObjectFromStringWithClassNoMapping() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.clear();;
    con.setTypeMap(typeMap);

    PreparedStatement pstmt = con.prepareStatement("SELECT ?::" + DAY_OF_WEEK_TYPE + " AS \"Yes\"");
    pstmt.setString(1, DayOfWeek.friday.name());
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    result.getObject(1, DayOfWeek.class); // TODO: Should we still support Enum in this case, even when not mapped?
    Assert.fail();
  }

  @Test
  public void testInsertAsString() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testdow VALUES (?::" + DAY_OF_WEEK_TYPE + ")");
    try {
      pstmt.setString(1, DayOfWeek.wednesday.name());
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }

    Statement stmt = con.createStatement();
    ResultSet result = stmt.executeQuery("SELECT * FROM testdow LIMIT 1");
    Assert.assertTrue(result.next());
    Assert.assertEquals(DayOfWeek.wednesday.name(), result.getString(1));
    Assert.assertEquals(DayOfWeek.wednesday.name(), result.getString("dow"));
    Assert.assertEquals(DayOfWeek.wednesday, result.getObject(1));
    Assert.assertEquals(DayOfWeek.wednesday, result.getObject("dow"));
    Assert.assertEquals(DayOfWeek.wednesday, result.getObject(1, DayOfWeek.class));
    Assert.assertEquals(DayOfWeek.wednesday, result.getObject("dow", DayOfWeek.class));
    Assert.assertFalse(result.next());
    stmt.close();
  }

  @Test
  public void testInsertAsObject() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testdow VALUES (?)");
    try {
      pstmt.setObject(1, DayOfWeek.tuesday);
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }

    Statement stmt = con.createStatement();
    ResultSet result = stmt.executeQuery("SELECT * FROM testdow LIMIT 1");
    Assert.assertTrue(result.next());
    Assert.assertEquals(DayOfWeek.tuesday.name(), result.getString(1));
    Assert.assertEquals(DayOfWeek.tuesday.name(), result.getString("dow"));
    Assert.assertEquals(DayOfWeek.tuesday, result.getObject(1));
    Assert.assertEquals(DayOfWeek.tuesday, result.getObject("dow"));
    Assert.assertEquals(DayOfWeek.tuesday, result.getObject(1, DayOfWeek.class));
    Assert.assertEquals(DayOfWeek.tuesday, result.getObject("dow", DayOfWeek.class));
    Assert.assertFalse(result.next());
    stmt.close();
  }

  @Test
  public void testInsertAsStringNoMapping() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    con.setTypeMap(Collections.<String, Class<?>>emptyMap());

    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testdow VALUES (?::" + DAY_OF_WEEK_TYPE + ")");
    try {
      pstmt.setString(1, DayOfWeek.wednesday.name());
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }

    Statement stmt = con.createStatement();
    ResultSet result = stmt.executeQuery("SELECT * FROM testdow LIMIT 1");
    Assert.assertTrue(result.next());
    Assert.assertEquals(DayOfWeek.wednesday.name(), result.getString(1));
    Assert.assertEquals(DayOfWeek.wednesday.name(), result.getString("dow"));
    Assert.assertEquals(DayOfWeek.wednesday, result.getObject(1, typeMap));
    Assert.assertEquals(DayOfWeek.wednesday, result.getObject("dow", typeMap));
    Assert.assertFalse(result.next());
    stmt.close();
  }

  @Test(expected = SQLException.class)
  public void testInsertAsObjectNoMapping() throws Exception {
    con.setTypeMap(Collections.<String, Class<?>>emptyMap());

    // Leave cast here, so failure will be due to the call being toString(), which doesn't match enum value
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testdow VALUES (?::" + DAY_OF_WEEK_TYPE + ")");
    try {
      pstmt.setObject(1, DayOfWeek.wednesday);
      pstmt.executeUpdate();
      Assert.fail();
    } finally {
      pstmt.close();
    }
  }

  // TODO: testArray

  // TODO: testArray2D
}
