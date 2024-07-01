/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Properties;

public class StructTest extends BaseTest4 {
  @BeforeClass
  public static void beforeAll() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createCompositeType(con, "inventory_item",
          "name text, supplier_id int, price numeric");
      TestUtil.createCompositeType(con, "inventory_group",
          "name text, item inventory_item, quantity int");
      TestUtil.createCompositeType(con, "nested_struct_item",
          "aint2 int2,"
              + "aint4 int4,"
              + "aoid oid,"
              + "aint8 int8,"
              + "afloat4 float4,"
              + "afloat8 float8,"
              + "achar char,"
              + "atext text,"
              + "aname name,"
              + "aboolean boolean,"
              + "abit bit,"
              + "adate date,"
              + "atime time,"
              + "atimestamp timestamp,"
              + "atimestamptz timestamptz,"
              + "ainventory_group inventory_group,"
              + "ajson json,"
              + "apoint point,"
              + "abox box,"
              + "abytea bytea");
      TestUtil.createTable(con, "nested_structs",
          "A nested_struct_item");

      TestUtil.createTable(con, "nested_structs",
          "id int,"
              + "A nested_struct_item");
      TestUtil.createTable(con, "nested_structs_array",
          "id int,"
              + "A nested_struct_item[]");
    }
  }

  @AfterClass
  public static void afterAll() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, "nested_structs_array");
      TestUtil.dropTable(con, "nested_structs");
      TestUtil.dropType(con, "nested_struct_item");
      TestUtil.dropType(con, "inventory_group");
      TestUtil.dropType(con, "inventory_item");
    }
  }

  @Before
  public void setUp() throws Exception {
    binaryMode = BinaryMode.FORCE;
    con = TestUtil.openDB();
  }

  @Override
  protected void forceBinary(Properties props) {
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, "nested_struct_item");
  }

  @Test
  public void getStructWithMap() throws SQLException {
    Struct inventoryItem = con.createStruct("inventory_item", new Object[]{"here", 42, 1.99});
    Struct inventoryGroup = con.createStruct("inventory_group", new Object[]{1, inventoryItem, 10});

    HashMap<String, Class<?>> map = new HashMap<>();
    map.put("text", String.class);
    map.put("inventory_item", String.class);
    map.put("int4", String.class);
    Object[] attributes = inventoryGroup.getAttributes(map);

    assertInstanceOf(String.class, attributes[0]);
    assertInstanceOf(String.class, attributes[1]);
    assertInstanceOf(String.class, attributes[2]);
  }

  @Test
  public void createNestedStructRaw() throws SQLException {
    Struct inventoryItem = con.createStruct("inventory_item", new Object[]{"here", 42, 1.99});
    Struct inventoryGroup = con.createStruct("inventory_group", new Object[]{1, inventoryItem, 10});
    Struct nestedStructItem = con.createStruct("nested_struct_item", new Object[]{0, 2, 3, 4, 6, 7, '9', '1', "test", true, '1', new Date(0), new Time(0), new Timestamp(0), new Timestamp(0), inventoryGroup, "{\"a\": 1, \"b\":2}", "(2,2)", "(1,1),(4,3)", new byte[]{65, 65}});

    PreparedStatement pstmt = con.prepareStatement("INSERT INTO nested_structs VALUES (1, ?)");
    pstmt.setObject(1, nestedStructItem);
    pstmt.executeUpdate();

    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT A FROM nested_structs WHERE Id = 1");

    assertTrue(rs.next());
    Struct s = (Struct) rs.getObject(1);
    assertEquals(nestedStructItem, s);
    assertFalse(rs.next());
  }

  @Test
  public void createArrayOfNestedStructRaw() throws SQLException {
    Struct inventoryItem = con.createStruct("inventory_item", new Object[]{"here", 42, 1.99});
    Struct inventoryGroup = con.createStruct("inventory_group", new Object[]{1, inventoryItem, 10});
    Struct nestedStructItem1 = con.createStruct("nested_struct_item", new Object[]{0, 2, 3, 4, 6, 7, '9', '1', "test", true, '1', new Date(0), new Time(0), new Timestamp(0), new Timestamp(0), inventoryGroup, "{\"a\": 1, \"b\":2}", "(2,2)", "(1,1),(4,3)", new byte[]{65, 65}});
    Struct nestedStructItem2 = con.createStruct("nested_struct_item", new Object[]{1, 2, 3, 4, 6, 7, '9', '1', "test", true, '1', new Date(0), new Time(0), new Timestamp(0), new Timestamp(0), inventoryGroup, "{\"a\": 1, \"b\":2}", "(2,2)", "(1,1),(4,3)", new byte[]{65, 65}});

    Array array = con.createArrayOf("nested_struct_item", new Object[]{nestedStructItem1, nestedStructItem2});

    PreparedStatement ps = con.prepareStatement("INSERT INTO nested_structs_array VALUES (1, ?)");
    ps.setArray(1, array);
    ps.executeUpdate();

    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT A FROM nested_structs_array WHERE Id = 1");

    assertTrue(rs.next());
    Array a = rs.getArray(1);
    assertEquals(nestedStructItem1, ((Struct[]) a.getArray())[0]);
    assertEquals(nestedStructItem2, ((Struct[]) a.getArray())[1]);
    assertFalse(rs.next());
  }

  @Test
  public void createNestedStructBinary() throws SQLException {
    Struct inventoryItem = con.createStruct("inventory_item", new Object[]{"here", 42, 1.99});
    Struct inventoryGroup = con.createStruct("inventory_group", new Object[]{1, inventoryItem, 10});
    Struct nestedStructItem = con.createStruct("nested_struct_item", new Object[]{0, 2, 3, 4, 6, 7, '9', '1', "test", true, '1', new Date(0), new Time(0), new Timestamp(0), new Timestamp(0), inventoryGroup, "{\"a\": 1, \"b\":2}", "(2,2)", "(1,1),(4,3)", new byte[]{65, 65}});

    PreparedStatement ps = con.prepareStatement("INSERT INTO nested_structs VALUES (2, ?)");
    ps.setObject(1, nestedStructItem);
    ps.executeUpdate();

    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT A FROM nested_structs WHERE Id = 2");

    assertTrue(rs.next());
    Struct s = (Struct) rs.getObject(1);
    assertEquals(nestedStructItem, s);
    assertFalse(rs.next());
  }
}
