/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PGobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code json[]}/{@code jsonb[]} decode to {@code String[]} (the legacy {@code getArray()} shape)
 * through the codec walker, while a scalar {@code json} column stays {@link PGobject}. Run under both
 * binary modes so the {@code FORCE} variant exercises the binary wire form (including the
 * {@code jsonb} version byte) end to end.
 */
@ParameterizedClass
@MethodSource("data")
public class JsonArrayRoundtripTest extends BaseTest4 {

  private Connection conn;

  public JsonArrayRoundtripTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    conn = con;
  }

  @Test
  public void jsonArrayReturnsStringArray() throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("SELECT ARRAY['{\"a\":1}', '2', null]::json[]");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      Object array = rs.getArray(1).getArray();
      assertInstanceOf(String[].class, array);
      assertArrayEquals(new String[]{"{\"a\":1}", "2", null}, (String[]) array);
    }
  }

  @Test
  public void jsonbArrayReturnsStringArray() throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("SELECT ARRAY['1', '2', null]::jsonb[]");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      Object array = rs.getArray(1).getArray();
      assertInstanceOf(String[].class, array);
      assertArrayEquals(new String[]{"1", "2", null}, (String[]) array);
    }
  }

  @Test
  public void jsonArraySliceReturnsStringArray() throws SQLException {
    // A 1-based [index, index+count) slice is taken from the codec-decoded array, not the legacy
    // decoder, so it must stay String[] in both text and binary.
    try (PreparedStatement ps = conn.prepareStatement("SELECT ARRAY['1', '2', '3', '4']::json[]");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      Object slice = rs.getArray(1).getArray(2, 2);
      assertInstanceOf(String[].class, slice);
      assertArrayEquals(new String[]{"2", "3"}, (String[]) slice);
    }
  }

  @Test
  public void jsonArrayWithTypeMapStaysStringArray() throws SQLException {
    // The type map targets composite/SQLData elements; for json (String elements) it is a no-op, so
    // json[] decodes fully through the codec walker even with a non-empty map, in text and binary.
    Map<String, Class<?>> map = new HashMap<>();
    map.put("anything", Integer.class);
    try (PreparedStatement ps = conn.prepareStatement("SELECT ARRAY['1', '2']::json[]");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      Object array = rs.getArray(1).getArray(map);
      assertInstanceOf(String[].class, array);
      assertArrayEquals(new String[]{"1", "2"}, (String[]) array);
    }
  }

  @Test
  public void scalarJsonStaysPGobject() throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("SELECT '{\"a\":1}'::json");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      Object o = rs.getObject(1);
      PGobject obj = assertInstanceOf(PGobject.class, o);
      assertEquals("{\"a\":1}", obj.getValue());
      assertEquals("{\"a\":1}", rs.getString(1));
    }
  }
}
