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
    Properties props = new Properties();
    try (Connection con = TestUtil.openDB(props)) {
      TestUtil.createCompositeType(con, "inventory_item",
          "name text, supplier_id int, price numeric");
      TestUtil.createCompositeType(con, "inventory_group",
          "name text, item inventory_item, quantity int");
      TestUtil.createCompositeType(con, "item_2d",
          "id int, arr inventory_item[][]");
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
      TestUtil.createTable(con, "item_2d_table", "id int, item item_2d");
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
      TestUtil.dropTable(con, "item_2d_table");
      TestUtil.dropType(con, "nested_struct_item");
      TestUtil.dropType(con, "inventory_group");
      TestUtil.dropType(con, "item_2d");
      TestUtil.dropType(con, "inventory_item");
    }
  }

  @Override
  protected void updateProperties(Properties props) {
    binaryMode = BinaryMode.FORCE;
    forceBinary(props);
  }

  @Override
  protected void forceBinary(Properties props) {
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, "item_2d");
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
    Struct nestedStructItem = con.createStruct("nested_struct_item", new Object[]{0, 2, 3, 4, 6.0, 7.0, '9', '1', "test", true, '1', new Date(0), new Time(0), new Timestamp(0), new Timestamp(0), inventoryGroup, "{\"a\": 1, \"b\":2}", "(2.0,2.0)", "(4.0,3.0),(1.0,1.0)", new byte[]{65, 65}});

    PreparedStatement pstmt = con.prepareStatement("INSERT INTO nested_structs VALUES (1, ?)");
    pstmt.setObject(1, nestedStructItem);
    pstmt.executeUpdate();

    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT A FROM nested_structs WHERE Id = 1");

    assertTrue(rs.next());
    Struct s = (Struct) rs.getObject(1);
    assertEquals(nestedStructItem.toString(), s.toString());
    assertFalse(rs.next());
  }

  @Test
  public void createArrayOfNestedStructRaw() throws SQLException {
    Struct inventoryItem = con.createStruct("inventory_item", new Object[]{"here", 42, 1.99});
    Struct inventoryGroup = con.createStruct("inventory_group", new Object[]{1, inventoryItem, 10});
    Struct nestedStructItem1 = con.createStruct("nested_struct_item", new Object[]{0, 2, 3, 4, 6.0, 7.0, '9', '1', "test", true, '1', new Date(0), new Time(0), new Timestamp(0), new Timestamp(0), inventoryGroup, "{\"a\": 1, \"b\":2}", "(2.0,2.0)", "(4.0,3.0),(1.0,1.0)", new byte[]{65, 65}});
    Struct nestedStructItem2 = con.createStruct("nested_struct_item", new Object[]{1, 2, 3, 4, 6.0, 7.0, '9', '1', "test", true, '1', new Date(0), new Time(0), new Timestamp(0), new Timestamp(0), inventoryGroup, "{\"a\": 1, \"b\":2}", "(2.0,2.0)", "(4.0,3.0),(1.0,1.0)", new byte[]{65, 65}});

    Array array = con.createArrayOf("nested_struct_item", new Object[]{nestedStructItem1, nestedStructItem2});

    PreparedStatement ps = con.prepareStatement("INSERT INTO nested_structs_array VALUES (1, ?)");
    ps.setArray(1, array);
    ps.executeUpdate();

    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT A FROM nested_structs_array WHERE Id = 1");

    assertTrue(rs.next());
    Array a = rs.getArray(1);
    assertEquals(nestedStructItem1.toString(), ((Struct[]) a.getArray())[0].toString());
    assertEquals(nestedStructItem2.toString(), ((Struct[]) a.getArray())[1].toString());
    assertFalse(rs.next());
  }

  @Test
  public void createStructWithInnerArrayBinary() throws SQLException {
    Struct i1 = con.createStruct("inventory_item", new Object[]{"here a", 42, 1.99});
    Struct i2 = con.createStruct("inventory_item", new Object[]{"here b", 42, 1.99});
    Struct i3 = con.createStruct("inventory_item", new Object[]{"here c", 42, 1.99});
    Array a1 = con.createArrayOf("inventory_item", new Object[] {i1, i2});
    Array a2 = con.createArrayOf("inventory_item", new Object[] {i3, i3});
    Struct s = con.createStruct("item_2d", new Object[] {1, con.createArrayOf("inventory_item", new Object[] {a1, a2})});
    PreparedStatement ps = con.prepareStatement("INSERT INTO item_2d_table VALUES(1, ?)");
    ps.setObject(1, s);
    ps.execute();

    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT item FROM item_2d_table WHERE id = 1");

    assertTrue(rs.next());
    Struct item = (Struct) rs.getObject(1);
    assertEquals(s, item);
    assertFalse(rs.next());
  }
}
