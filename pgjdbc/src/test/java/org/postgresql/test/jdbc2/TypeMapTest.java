/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.postgresql.core.BaseConnection;
import org.postgresql.test.util.InsaneClass;
import org.postgresql.test.util.InsaneInterfaceHierachy;

import org.junit.Test;

import java.io.Serializable;
//#if mvn.project.property.postgresql.jdbc.spec < "JDBC4.1"
import java.sql.SQLFeatureNotSupportedException;
//#endif
import java.util.Map;

/**
 * This object tests the type map implementation in {@link PgConnection}, along
 * with the inverted forms of the map used for type inference.
 */
public class TypeMapTest extends BaseTest4 {

  @Test
      //#if mvn.project.property.postgresql.jdbc.spec < "JDBC4.1"
      (expected = SQLFeatureNotSupportedException.class)
  //#endif
  public void testTypeMapNotNull() throws Exception {
    assertNotNull(
        "Per the specification, the type map will be empty when not types set - not null",
        con.getTypeMap());
  }

  @Test
      //#if mvn.project.property.postgresql.jdbc.spec < "JDBC4.1"
      (expected = SQLFeatureNotSupportedException.class)
  //#endif
  public void testTypeMapEmpty() throws Exception {
    assertEquals(
        "We expect any empty type map on a new connection",
        0,
        con.getTypeMap().size());
  }

  @Test
      //#if mvn.project.property.postgresql.jdbc.spec < "JDBC4.1"
      (expected = SQLFeatureNotSupportedException.class)
  //#endif
  public void testTypeMapDefensiveCopy() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("test1", Integer.class);
    con.setTypeMap(typeMap);

    Map<String, Class<?>> typeMap1 = con.getTypeMap();
    assertEquals(
        "Type map should be equal after defensive copying in setTypeMap",
        typeMap,
        typeMap1);

    typeMap.put("test2", Double.class);
    Map<String, Class<?>> typeMap2 = con.getTypeMap();

    assertEquals(
        "Type map should not have been modified by changes after setTypeMap",
        typeMap1,
        typeMap2);
  }

  /**
   * This test will operate slowly, or not complete at all when
   * {@link InsaneInterfaceHierachy#DEPTH} is turned-up.  Testing
   * the number of operations would require cluttering up
   * {@link PgConnection#setTypeMap(java.util.Map)}.
   */
  @Test
      //#if mvn.project.property.postgresql.jdbc.spec < "JDBC4.1"
      (expected = SQLFeatureNotSupportedException.class)
  //#endif
  public void testInsaneInferenceMap() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("insane_class", InsaneClass.class);
    con.setTypeMap(typeMap);
  }

  @Test
      //#if mvn.project.property.postgresql.jdbc.spec < "JDBC4.1"
      (expected = SQLFeatureNotSupportedException.class)
  //#endif
  public void testInvertedMapMultipleDirect() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("Integer 1", Integer.class);
    typeMap.put("Integer 2", Integer.class);
    con.setTypeMap(typeMap);

    BaseConnection baseConnection = con.unwrap(BaseConnection.class);

    assertEquals(2, baseConnection.getTypeMapInvertedDirect(Integer.class).size());
    assertEquals(0, baseConnection.getTypeMapInvertedDirect(Number.class).size());
    assertEquals(0, baseConnection.getTypeMapInvertedDirect(Serializable.class).size());
    assertEquals(0, baseConnection.getTypeMapInvertedDirect(Comparable.class).size());
    assertEquals(0, baseConnection.getTypeMapInvertedDirect(Object.class).size());

    assertEquals(2, baseConnection.getTypeMapInvertedInherited(Integer.class).size());
    assertEquals(2, baseConnection.getTypeMapInvertedInherited(Number.class).size());
    assertEquals(2, baseConnection.getTypeMapInvertedInherited(Serializable.class).size());
    assertEquals(2, baseConnection.getTypeMapInvertedInherited(Comparable.class).size());
    assertEquals(2, baseConnection.getTypeMapInvertedInherited(Object.class).size());
  }

  @Test
      //#if mvn.project.property.postgresql.jdbc.spec < "JDBC4.1"
      (expected = SQLFeatureNotSupportedException.class)
  //#endif
  public void testInvertedMapMultipleInherited() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("Integer", Integer.class);
    typeMap.put("Float", Float.class);
    con.setTypeMap(typeMap);

    BaseConnection baseConnection = con.unwrap(BaseConnection.class);

    assertEquals(1, baseConnection.getTypeMapInvertedDirect(Integer.class).size());
    assertEquals(1, baseConnection.getTypeMapInvertedDirect(Float.class).size());
    assertEquals(0, baseConnection.getTypeMapInvertedDirect(Number.class).size());
    assertEquals(0, baseConnection.getTypeMapInvertedDirect(Serializable.class).size());
    assertEquals(0, baseConnection.getTypeMapInvertedDirect(Comparable.class).size());
    assertEquals(0, baseConnection.getTypeMapInvertedDirect(Object.class).size());

    assertEquals(1, baseConnection.getTypeMapInvertedInherited(Integer.class).size());
    assertEquals(1, baseConnection.getTypeMapInvertedInherited(Float.class).size());
    assertEquals(2, baseConnection.getTypeMapInvertedInherited(Number.class).size());
    assertEquals(2, baseConnection.getTypeMapInvertedInherited(Serializable.class).size());
    assertEquals(2, baseConnection.getTypeMapInvertedInherited(Comparable.class).size());
    assertEquals(2, baseConnection.getTypeMapInvertedInherited(Object.class).size());
  }

  @Test
      //#if mvn.project.property.postgresql.jdbc.spec < "JDBC4.1"
      (expected = SQLFeatureNotSupportedException.class)
  //#endif
  public void testInvertedMapMultipleDirectAndInherited() throws Exception {
    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put("Integer", Integer.class);
    typeMap.put("Float", Float.class);
    typeMap.put("Number", Number.class);
    con.setTypeMap(typeMap);

    BaseConnection baseConnection = con.unwrap(BaseConnection.class);

    assertEquals(1, baseConnection.getTypeMapInvertedDirect(Integer.class).size());
    assertEquals(1, baseConnection.getTypeMapInvertedDirect(Float.class).size());
    assertEquals(1, baseConnection.getTypeMapInvertedDirect(Number.class).size());
    assertEquals(0, baseConnection.getTypeMapInvertedDirect(Serializable.class).size());
    assertEquals(0, baseConnection.getTypeMapInvertedDirect(Comparable.class).size());
    assertEquals(0, baseConnection.getTypeMapInvertedDirect(Object.class).size());

    assertEquals(1, baseConnection.getTypeMapInvertedInherited(Integer.class).size());
    assertEquals(1, baseConnection.getTypeMapInvertedInherited(Float.class).size());
    assertEquals(3, baseConnection.getTypeMapInvertedInherited(Number.class).size());
    assertEquals(3, baseConnection.getTypeMapInvertedInherited(Serializable.class).size());
    assertEquals(2, baseConnection.getTypeMapInvertedInherited(Comparable.class).size());
    assertEquals(3, baseConnection.getTypeMapInvertedInherited(Object.class).size());
  }

  // TODO: Test complex interface hierarchy, to make sure interfaces extended by
  //       interfaces implemented by base classes are all matched.

  // TODO: Add tests to make sure classes and interfaces are distinguished correctly
  //       in the direct inverted map.

  // TODO: Add tests that getTypeMapInvertedDirect and getTypeMapInvertedInherited
  //       are recreated correctly
}
