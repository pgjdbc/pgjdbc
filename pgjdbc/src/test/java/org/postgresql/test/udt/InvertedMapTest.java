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
import org.postgresql.udt.UdtMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

/**
 * This object tests the inverted forms of the map used for type inference.
 */
public class InvertedMapTest {
  private BaseConnection con;

  // Set up the fixture for this testcase: the tables for this test.
  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB().unwrap(BaseConnection.class);
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
   * {@link UdtMap#getInvertedInherited(java.lang.Class)}.
   */
  @Test
  public void testInsaneInferenceMap() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("insane_class", InsaneClass.class);
    typeMap.put("insane_interface", InsaneInterface.class);
    con.setTypeMap(typeMap);

    UdtMap udtMap = con.getUdtMap();

    assertEquals(
        Collections.singleton("insane_class"),
        udtMap.getInvertedDirect(InsaneClass.class));
    assertEquals(
        Collections.singleton("insane_interface"),
        udtMap.getInvertedDirect(InsaneInterface.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(InsaneInterfaceHierachy.TestHierarchy_1_1.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(InsaneInterfaceHierachy.TestHierarchy_1_2.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Object.class));

    assertEquals(
        Collections.singleton("insane_class"),
        udtMap.getInvertedInherited(InsaneClass.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("insane_class", "insane_interface")),
        udtMap.getInvertedInherited(InsaneInterface.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("insane_class", "insane_interface")),
        udtMap.getInvertedInherited(InsaneInterfaceHierachy.TestHierarchy_1_1.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("insane_class", "insane_interface")),
        udtMap.getInvertedInherited(InsaneInterfaceHierachy.TestHierarchy_1_2.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedInherited(Object.class));
  }

  @Test
  public void testInvertedMapMultipleDirect() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("Integer 1", Integer.class);
    typeMap.put("Integer 2", Integer.class);
    con.setTypeMap(typeMap);

    UdtMap udtMap = con.getUdtMap();

    assertEquals(
        new HashSet<String>(Arrays.asList("Integer 1", "Integer 2")),
        udtMap.getInvertedDirect(Integer.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Number.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Serializable.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Comparable.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Object.class));

    assertEquals(
        new HashSet<String>(Arrays.asList("Integer 1", "Integer 2")),
        udtMap.getInvertedInherited(Integer.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer 1", "Integer 2")),
        udtMap.getInvertedInherited(Number.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer 1", "Integer 2")),
        udtMap.getInvertedInherited(Serializable.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer 1", "Integer 2")),
        udtMap.getInvertedInherited(Comparable.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedInherited(Object.class));
  }

  @Test
  public void testInvertedMapMultipleInherited() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("Integer", Integer.class);
    typeMap.put("Float", Float.class);
    con.setTypeMap(typeMap);

    UdtMap udtMap = con.getUdtMap();

    assertEquals(
        Collections.singleton("Integer"),
        udtMap.getInvertedDirect(Integer.class));
    assertEquals(
        Collections.singleton("Float"),
        udtMap.getInvertedDirect(Float.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Number.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Serializable.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Comparable.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Object.class));

    assertEquals(
        Collections.singleton("Integer"),
        udtMap.getInvertedInherited(Integer.class));
    assertEquals(
        Collections.singleton("Float"),
        udtMap.getInvertedInherited(Float.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float")),
        udtMap.getInvertedInherited(Number.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float")),
        udtMap.getInvertedInherited(Serializable.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float")),
        udtMap.getInvertedInherited(Comparable.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedInherited(Object.class));
  }

  @Test
  public void testInvertedMapMultipleDirectAndInherited() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("Integer", Integer.class);
    typeMap.put("Float", Float.class);
    typeMap.put("Number", Number.class);
    con.setTypeMap(typeMap);

    UdtMap udtMap = con.getUdtMap();

    assertEquals(
        Collections.singleton("Integer"),
        udtMap.getInvertedDirect(Integer.class));
    assertEquals(
        Collections.singleton("Float"),
        udtMap.getInvertedDirect(Float.class));
    assertEquals(
        Collections.singleton("Number"),
        udtMap.getInvertedDirect(Number.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Serializable.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Comparable.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Object.class));

    assertEquals(
        Collections.singleton("Integer"),
        udtMap.getInvertedInherited(Integer.class));
    assertEquals(
        Collections.singleton("Float"),
        udtMap.getInvertedInherited(Float.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float", "Number")),
        udtMap.getInvertedInherited(Number.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float", "Number")),
        udtMap.getInvertedInherited(Serializable.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float")),
        udtMap.getInvertedInherited(Comparable.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedInherited(Object.class));
  }

  @Test
  public void testInvertedMapDistinguishesClassAndInterface() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("Comparable", Comparable.class);
    // Integer implements Comparable - make sure it is registered as Integer only and not its interface
    typeMap.put("Integer", Integer.class);
    con.setTypeMap(typeMap);

    UdtMap udtMap = con.getUdtMap();

    assertEquals(
        Collections.singleton("Comparable"),
        udtMap.getInvertedDirect(Comparable.class));
    assertEquals(
        Collections.singleton("Integer"),
        udtMap.getInvertedDirect(Integer.class));
  }

  @Test
  public void testInvertedMapsRebuilt() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("Integer", Integer.class);
    con.setTypeMap(typeMap);

    UdtMap udtMap = con.getUdtMap();

    assertEquals(
        Collections.singleton("Integer"),
        udtMap.getInvertedDirect(Integer.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Number.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Float.class));

    assertEquals(
        Collections.singleton("Integer"),
        udtMap.getInvertedInherited(Integer.class));
    assertEquals(
        Collections.singleton("Integer"),
        udtMap.getInvertedInherited(Number.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedInherited(Float.class));

    typeMap.put("Float", Float.class);
    con.setTypeMap(typeMap);
    udtMap = con.getUdtMap();

    assertEquals(
        Collections.singleton("Integer"),
        udtMap.getInvertedDirect(Integer.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Number.class));
    assertEquals(
        Collections.singleton("Float"),
        udtMap.getInvertedDirect(Float.class));

    assertEquals(
        Collections.singleton("Integer"),
        udtMap.getInvertedInherited(Integer.class));
    assertEquals(
        new HashSet<String>(Arrays.asList("Integer", "Float")),
        udtMap.getInvertedInherited(Number.class));
    assertEquals(
        Collections.singleton("Float"),
        udtMap.getInvertedInherited(Float.class));
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

    UdtMap udtMap = con.getUdtMap();

    assertEquals(
        Collections.singleton("TestIndirectClass"),
        udtMap.getInvertedDirect(TestIndirectClass.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(TestIndirectBase.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(IndirectFromBase.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(IndirectlyExtendedFromBase.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedDirect(Object.class));

    assertEquals(
        Collections.singleton("TestIndirectClass"),
        udtMap.getInvertedInherited(TestIndirectClass.class));
    assertEquals(
        Collections.singleton("TestIndirectClass"),
        udtMap.getInvertedInherited(TestIndirectBase.class));
    assertEquals(
        Collections.singleton("TestIndirectClass"),
        udtMap.getInvertedInherited(IndirectFromBase.class));
    assertEquals(
        Collections.singleton("TestIndirectClass"),
        udtMap.getInvertedInherited(IndirectlyExtendedFromBase.class));
    assertEquals(
        Collections.emptySet(),
        udtMap.getInvertedInherited(Object.class));
  }

  @Test
  public void testCustomTypeToObject() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("Object", Object.class);
    typeMap.put("Number", Number.class);
    typeMap.put("Serializable", Serializable.class);
    typeMap.put("Integer", Integer.class);
    con.setTypeMap(typeMap);

    UdtMap udtMap = con.getUdtMap();

    assertEquals(
        "Direct mappings are allowed to Object",
        Collections.singleton("Object"),
        udtMap.getInvertedDirect(Object.class));

    assertEquals(
        "Inverted maps are not set for Object",
        Collections.emptySet(),
        udtMap.getInvertedInherited(Object.class));
  }
}
