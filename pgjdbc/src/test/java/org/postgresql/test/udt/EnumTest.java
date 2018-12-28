/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PGobject;

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

// TODO: Consider Enum support being Types.JAVA_OBJECT instead of Types.OTHER
public class EnumTest extends BaseTest4 {

  protected static final String SCHEMA = "\"org.postgresql\"";
  // TODO: Quotes needed on this type, due to quotes needed on schema.  This is inconsistent with
  //       psql that would be type: "org.postgresql".dayofweek
  protected static final String DAY_OF_WEEK_TYPE = SCHEMA + ".\"dayofweek\"";

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();


    TestUtil.createSchema(con, SCHEMA);

    assumeMinimumServerVersion(ServerVersion.v8_3);

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
  @Override
  public void tearDown() throws SQLException {
    try {
      TestUtil.closeDB(con);

      con = TestUtil.openDB();

      TestUtil.dropTable(con, "testdow");
      TestUtil.dropType(con, DAY_OF_WEEK_TYPE);
      TestUtil.dropSchema(con, SCHEMA);
    } finally {
      super.tearDown();
    }
  }

  @Test
  public void testGetStringFromString() throws Exception {
    // :: cast required on setString:
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::" + DAY_OF_WEEK_TYPE + " AS \"AmbiguousName\", ?::" + DAY_OF_WEEK_TYPE + " AS \"ambiguousname\"");
    pstmt.setString(1, DayOfWeek.friday.name());
    pstmt.setString(2, DayOfWeek.tuesday.name());
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    Assert.assertEquals(DayOfWeek.friday.name(), result.getString(1));
    Assert.assertEquals(DayOfWeek.friday.name(), result.getString("AmbiguousName"));
    // TODO: Fails: Seems the driver is forcing case-insensitivity: Assert.assertEquals(DayOfWeek.tuesday.name(), result.getString("ambiguousname"));
    // TODO: Fix, if desired, could be to map the name twice - once case-sensitive once as done now.  Then look in case-sensitive map first.
    Assert.assertFalse(result.next());
    pstmt.close();
  }

  @Test
  public void testGetStringFromObject() throws Exception {
    // :: cast not needed on setObject:
    PreparedStatement pstmt = con.prepareStatement("SELECT ? AS \"CapitalName\"");
    pstmt.setObject(1, DayOfWeek.friday);
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    Assert.assertEquals(DayOfWeek.friday.name(), result.getString(1));
    Assert.assertEquals(DayOfWeek.friday.name(), result.getString("CapitalName"));
    Assert.assertFalse(result.next());
    pstmt.close();
  }

  @Test
  public void testGetObjectFromString() throws Exception {
    // :: cast required on setString:
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::" + DAY_OF_WEEK_TYPE + " AS \"lowername\"");
    pstmt.setString(1, DayOfWeek.friday.name());
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    Assert.assertSame(DayOfWeek.friday, result.getObject(1));
    Assert.assertSame(DayOfWeek.friday, result.getObject("lowername"));
    Assert.assertFalse(result.next());
    pstmt.close();
  }

  @Test
  public void testGetObjectFromObject() throws Exception {
    // :: cast not needed on setObject:
    PreparedStatement pstmt = con.prepareStatement("SELECT ? AS \"lowername2\"");
    pstmt.setObject(1, DayOfWeek.friday);
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    // TODO: This fails because the pgType is just "dayofweek" for some odd reason:
    // Assert.assertSame(DayOfWeek.friday, result.getObject(1));
    // TODO: This fails because the pgType is just "dayofweek" for some odd reason:
    // Assert.assertSame(DayOfWeek.friday, result.getObject("lowername2"));
    Assert.assertFalse(result.next());
    pstmt.close();
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test
  public void testGetObjectFromStringWithClassDirect() throws Exception {
    // This should work without relying on the inference
    // TODO: Option to turn on/off inference, turn off here to make sure still works
    // :: cast required on setString:
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::" + DAY_OF_WEEK_TYPE + " AS noquotename");
    pstmt.setString(1, DayOfWeek.friday.name());
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    Assert.assertSame(DayOfWeek.friday, result.getObject(1, DayOfWeek.class));
    Assert.assertSame(DayOfWeek.friday, result.getObject("noquotename", DayOfWeek.class));
    Assert.assertFalse(result.next());
    pstmt.close();
  }
  //#endif

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test
  public void testGetObjectFromObjectWithClassDirect() throws Exception {
    // This should work without relying on the inference
    // TODO: Option to turn on/off inference, turn off here to make sure still works
    // :: cast not needed on setObject:
    PreparedStatement pstmt = con.prepareStatement("SELECT ? AS noquotename");
    pstmt.setObject(1, DayOfWeek.friday);
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    Assert.assertSame(DayOfWeek.friday, result.getObject(1, DayOfWeek.class));
    Assert.assertSame(DayOfWeek.friday, result.getObject("noquotename", DayOfWeek.class));
    Assert.assertFalse(result.next());
    pstmt.close();
  }
  //#endif

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test
  public void testGetObjectFromStringWithClassInferred() throws Exception {
    // Added ::text cast to force inference to map back to requested type
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::" + DAY_OF_WEEK_TYPE + "::text AS noquotename");
    pstmt.setString(1, DayOfWeek.friday.name());
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    Assert.assertSame(DayOfWeek.friday, result.getObject(1, DayOfWeek.class));
    Assert.assertSame(DayOfWeek.friday, result.getObject("noquotename", DayOfWeek.class));
    Assert.assertFalse(result.next());
    pstmt.close();
    // TODO: Do another method like this, but giving an invalid value, to make sure the type is handled as ENUM server-side before being sent back to text
  }
  // TODO: Option to turn on/off inference, turn off here to make sure fails
  //#endif

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test
  public void testGetObjectFromObjectWithClassInferred() throws Exception {
    // Added ::text cast to force inference to map back to requested type
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::text AS noquotename");
    pstmt.setObject(1, DayOfWeek.friday);
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    Assert.assertSame(DayOfWeek.friday, result.getObject(1, DayOfWeek.class));
    Assert.assertSame(DayOfWeek.friday, result.getObject("noquotename", DayOfWeek.class));
    Assert.assertFalse(result.next());
    pstmt.close();
    // TODO: Do another method like this, but giving an invalid value, to make sure the type is handled as ENUM server-side before being sent back to text
  }
  // TODO: Option to turn on/off inference, turn off here to make sure fails
  //#endif

  @Test
  public void testGetObjectFromStringOverrideToNoMapping() throws Exception {
    // :: cast required on setString:
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::" + DAY_OF_WEEK_TYPE + " AS will_be_pgobject");
    pstmt.setString(1, DayOfWeek.friday.name());
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    // Should come back as PGobject when enum OID not in current typemap
    PGobject obj1 = (PGobject)result.getObject(1, Collections.<String, Class<?>>emptyMap());
    Assert.assertEquals(DayOfWeek.friday.name(), obj1.getValue());
    PGobject obj2 = (PGobject)result.getObject("will_be_pgobject", Collections.<String, Class<?>>emptyMap());
    Assert.assertEquals(DayOfWeek.friday.name(), obj2.getValue());
    Assert.assertFalse(result.next());
    pstmt.close();
  }

  @Test
  public void testGetObjectFromStringOverrideToHaveMapping() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    con.setTypeMap(Collections.<String, Class<?>>emptyMap());

    // :: cast required on setString:
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::" + DAY_OF_WEEK_TYPE + " AS NoQuoteCapitalName");
    pstmt.setString(1, DayOfWeek.friday.name());
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    Assert.assertSame(DayOfWeek.friday, result.getObject(1, typeMap));
    Assert.assertSame(DayOfWeek.friday, result.getObject("NoQuoteCapitalName", typeMap));
    Assert.assertSame(DayOfWeek.friday, result.getObject("noquotecapitalname", typeMap));
    Assert.assertFalse(result.next());
    pstmt.close();
  }

  @Test
  public void testGetObjectFromStringNoMapping() throws Exception {
    // Setting to null will clear the typemap
    con.setTypeMap(null);

    // :: cast required on setString:
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::" + DAY_OF_WEEK_TYPE + " AS will_be_pgobject");
    pstmt.setString(1, DayOfWeek.monday.name());
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    // Should come back as PGobject when enum OID not in current typemap
    PGobject obj1 = (PGobject)result.getObject(1);
    Assert.assertEquals(DayOfWeek.monday.name(), obj1.getValue());
    PGobject obj2 = (PGobject)result.getObject("will_be_pgobject");
    Assert.assertEquals(DayOfWeek.monday.name(), obj2.getValue());
    Assert.assertFalse(result.next());
    pstmt.close();
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test(expected = SQLException.class)
  public void testGetObjectFromStringWithClassNoMapping() throws Exception {
    // May also set to empty map to clear the typemap
    con.setTypeMap(Collections.<String, Class<?>>emptyMap());

    // :: cast required on setString:
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::" + DAY_OF_WEEK_TYPE + " AS failure");
    pstmt.setString(1, DayOfWeek.friday.name());
    ResultSet result = pstmt.executeQuery();
    Assert.assertTrue(result.next());
    result.getObject(1, DayOfWeek.class); // TODO: Should we still support Enum in this case, even when not mapped? TODO: Test different EnumMode
    Assert.fail();
  }
  //#endif

  @Test
  public void testInsertAsString() throws Exception {
    // :: cast required on setString:
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
    Assert.assertSame(DayOfWeek.wednesday, result.getObject(1));
    Assert.assertSame(DayOfWeek.wednesday, result.getObject("dow"));
    //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
    Assert.assertSame(DayOfWeek.wednesday, result.getObject(1, DayOfWeek.class));
    Assert.assertSame(DayOfWeek.wednesday, result.getObject("dow", DayOfWeek.class));
    //#endif
    Assert.assertFalse(result.next());
    stmt.close();
  }

  @Test
  public void testInsertAsObject() throws Exception {
    // :: cast not needed on setObject:
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
    // TODO: This fails because the pgType is just "dayofweek" for some odd reason:
    // Assert.assertSame(DayOfWeek.tuesday, result.getObject(1));
    // TODO: This fails because the pgType is just "dayofweek" for some odd reason:
    // Assert.assertSame(DayOfWeek.tuesday, result.getObject("dow"));
    //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
    Assert.assertSame(DayOfWeek.tuesday, result.getObject(1, DayOfWeek.class));
    Assert.assertSame(DayOfWeek.tuesday, result.getObject("dow", DayOfWeek.class));
    //#endif
    Assert.assertFalse(result.next());
    stmt.close();
  }

  @Test
  public void testInsertAsStringNoMapping() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    con.setTypeMap(Collections.<String, Class<?>>emptyMap());

    // :: cast required on setString:
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
    Assert.assertSame(DayOfWeek.wednesday, result.getObject(1, typeMap));
    Assert.assertSame(DayOfWeek.wednesday, result.getObject("dow", typeMap));
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
