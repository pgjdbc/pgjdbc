/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import static org.junit.Assert.assertEquals;

import org.postgresql.core.BaseConnection;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.InsaneClass;
import org.postgresql.test.util.InsaneInterface;
import org.postgresql.test.util.InsaneInterfaceHierachy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.Serializable;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

/**
 * This object tests the inverted forms of the map used for type inference.
 */
public class InvertedMapTest {
  private Connection con;

  // Set up the fixture for this testcase: the tables for this test.
  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();
  }

  // Tear down the fixture for this test case.
  @After
  public void tearDown() throws Exception {
    TestUtil.closeDB(con);
  }

  /**
   * This test will operate slowly, or not complete at all when
   * {@link InsaneInterfaceHierachy#DEPTH} is turned-up.  At the current
   * hierarchy depth, it makes a conspicuously long log file when the
   * implementation is broken and not pruning visited nodes correctly.  Testing
   * the number of operations would require cluttering up
   * {@link PgConnection#getTypeMapInvertedInherited(java.lang.Class)}.
   */
  @Test
  public void testInsaneInferenceMap() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("insane_class", InsaneClass.class);
    typeMap.put("insane_interface", InsaneInterface.class);
    con.setTypeMap(typeMap);

    BaseConnection baseConnection = con.unwrap(BaseConnection.class);

    assertEquals(
        Collections.singleton("insane_class"),
        baseConnection.getTypeMapInvertedDirect(InsaneClass.class));
    assertEquals(
        Collections.singleton("insane_interface"),
        baseConnection.getTypeMapInvertedDirect(InsaneInterface.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(InsaneInterfaceHierachy.TestHierarchy_1_1.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(InsaneInterfaceHierachy.TestHierarchy_1_2.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Object.class));

    assertEquals(
        Collections.singleton("insane_class"),
        baseConnection.getTypeMapInvertedInherited(InsaneClass.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("insane_class", "insane_interface")),
        baseConnection.getTypeMapInvertedInherited(InsaneInterface.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("insane_class", "insane_interface")),
        baseConnection.getTypeMapInvertedInherited(InsaneInterfaceHierachy.TestHierarchy_1_1.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("insane_class", "insane_interface")),
        baseConnection.getTypeMapInvertedInherited(InsaneInterfaceHierachy.TestHierarchy_1_2.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("insane_class", "insane_interface")),
        baseConnection.getTypeMapInvertedInherited(Object.class));
  }

  @Test
  public void testInvertedMapMultipleDirect() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("Integer 1", Integer.class);
    typeMap.put("Integer 2", Integer.class);
    con.setTypeMap(typeMap);

    BaseConnection baseConnection = con.unwrap(BaseConnection.class);

    assertEquals(
        new HashSet<String>(Arrays.asList("Integer 1", "Integer 2")),
        baseConnection.getTypeMapInvertedDirect(Integer.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Number.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Serializable.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Comparable.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Object.class));

    assertEquals(
        new HashSet<String>(Arrays.asList("Integer 1", "Integer 2")),
        baseConnection.getTypeMapInvertedInherited(Integer.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer 1", "Integer 2")),
        baseConnection.getTypeMapInvertedInherited(Number.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer 1", "Integer 2")),
        baseConnection.getTypeMapInvertedInherited(Serializable.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer 1", "Integer 2")),
        baseConnection.getTypeMapInvertedInherited(Comparable.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer 1", "Integer 2")),
        baseConnection.getTypeMapInvertedInherited(Object.class));
  }

  @Test
  public void testInvertedMapMultipleInherited() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("Integer", Integer.class);
    typeMap.put("Float", Float.class);
    con.setTypeMap(typeMap);

    BaseConnection baseConnection = con.unwrap(BaseConnection.class);

    assertEquals(
        Collections.singleton("Integer"),
        baseConnection.getTypeMapInvertedDirect(Integer.class));
    assertEquals(
        Collections.singleton("Float"),
        baseConnection.getTypeMapInvertedDirect(Float.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Number.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Serializable.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Comparable.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Object.class));

    assertEquals(
        Collections.singleton("Integer"),
        baseConnection.getTypeMapInvertedInherited(Integer.class));
    assertEquals(
        Collections.singleton("Float"),
        baseConnection.getTypeMapInvertedInherited(Float.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float")),
        baseConnection.getTypeMapInvertedInherited(Number.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float")),
        baseConnection.getTypeMapInvertedInherited(Serializable.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float")),
        baseConnection.getTypeMapInvertedInherited(Comparable.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float")),
        baseConnection.getTypeMapInvertedInherited(Object.class));
  }

  @Test
  public void testInvertedMapMultipleDirectAndInherited() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("Integer", Integer.class);
    typeMap.put("Float", Float.class);
    typeMap.put("Number", Number.class);
    con.setTypeMap(typeMap);

    BaseConnection baseConnection = con.unwrap(BaseConnection.class);

    assertEquals(
        Collections.singleton("Integer"),
        baseConnection.getTypeMapInvertedDirect(Integer.class));
    assertEquals(
        Collections.singleton("Float"),
        baseConnection.getTypeMapInvertedDirect(Float.class));
    assertEquals(
        Collections.singleton("Number"),
        baseConnection.getTypeMapInvertedDirect(Number.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Serializable.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Comparable.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Object.class));

    assertEquals(
        Collections.singleton("Integer"),
        baseConnection.getTypeMapInvertedInherited(Integer.class));
    assertEquals(
        Collections.singleton("Float"),
        baseConnection.getTypeMapInvertedInherited(Float.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float", "Number")),
        baseConnection.getTypeMapInvertedInherited(Number.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float", "Number")),
        baseConnection.getTypeMapInvertedInherited(Serializable.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float")),
        baseConnection.getTypeMapInvertedInherited(Comparable.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float", "Number")),
        baseConnection.getTypeMapInvertedInherited(Object.class));
  }

  @Test
  public void testInvertedMapDistinguishesClassAndInterface() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("Comparable", Comparable.class);
    // Integer implements Comparable - make sure it is registered as Integer only and not its interface
    typeMap.put("Integer", Integer.class);
    con.setTypeMap(typeMap);

    BaseConnection baseConnection = con.unwrap(BaseConnection.class);

    assertEquals(
        Collections.singleton("Comparable"),
        baseConnection.getTypeMapInvertedDirect(Comparable.class));
    assertEquals(
        Collections.singleton("Integer"),
        baseConnection.getTypeMapInvertedDirect(Integer.class));
  }

  @Test
  public void testInvertedMapsRebuilt() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("Integer", Integer.class);
    con.setTypeMap(typeMap);

    BaseConnection baseConnection = con.unwrap(BaseConnection.class);

    assertEquals(
        Collections.singleton("Integer"),
        baseConnection.getTypeMapInvertedDirect(Integer.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Number.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Float.class));

    assertEquals(
        Collections.singleton("Integer"),
        baseConnection.getTypeMapInvertedInherited(Integer.class));
    assertEquals(
        Collections.singleton("Integer"),
        baseConnection.getTypeMapInvertedInherited(Number.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedInherited(Float.class));

    typeMap.put("Float", Float.class);
    con.setTypeMap(typeMap);

    assertEquals(
        Collections.singleton("Integer"),
        baseConnection.getTypeMapInvertedDirect(Integer.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Number.class));
    assertEquals(
        Collections.singleton("Float"),
        baseConnection.getTypeMapInvertedDirect(Float.class));

    assertEquals(
        Collections.singleton("Integer"),
        baseConnection.getTypeMapInvertedInherited(Integer.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float")),
        baseConnection.getTypeMapInvertedInherited(Number.class));
    assertEquals(
        Collections.singleton("Float"),
        baseConnection.getTypeMapInvertedInherited(Float.class));
  }

  public interface IndirectlyExtendedFromBase {}

  public interface IndirectFromBase extends IndirectlyExtendedFromBase {}

  public abstract class TestIndirectBase implements IndirectFromBase {}

  public class TestIndirectClass extends TestIndirectBase {}

  /**
   * Tests that interfaces extended by interfaces implemented by base classes
   * are included.
   */
  @Test
  public void testIndirectExtendedInterfaces() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("TestIndirectClass", TestIndirectClass.class);
    con.setTypeMap(typeMap);

    BaseConnection baseConnection = con.unwrap(BaseConnection.class);

    assertEquals(
        Collections.singleton("TestIndirectClass"),
        baseConnection.getTypeMapInvertedDirect(TestIndirectClass.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(TestIndirectBase.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(IndirectFromBase.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(IndirectlyExtendedFromBase.class));
    assertEquals(
        Collections.emptySet(),
        baseConnection.getTypeMapInvertedDirect(Object.class));

    assertEquals(
        Collections.singleton("TestIndirectClass"),
        baseConnection.getTypeMapInvertedInherited(TestIndirectClass.class));
    assertEquals(
        Collections.singleton("TestIndirectClass"),
        baseConnection.getTypeMapInvertedInherited(TestIndirectBase.class));
    assertEquals(
        Collections.singleton("TestIndirectClass"),
        baseConnection.getTypeMapInvertedInherited(IndirectFromBase.class));
    assertEquals(
        Collections.singleton("TestIndirectClass"),
        baseConnection.getTypeMapInvertedInherited(IndirectlyExtendedFromBase.class));
    assertEquals(
        Collections.singleton("TestIndirectClass"),
        baseConnection.getTypeMapInvertedInherited(Object.class));
  }
}
