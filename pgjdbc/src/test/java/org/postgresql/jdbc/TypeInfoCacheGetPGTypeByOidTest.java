/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;
import static org.postgresql.jdbc.TypeInfoCacheTestParameters.getPGTypeByOidParams;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeStruct;

import org.junit.Test;
import org.junit.runners.Parameterized;

import java.sql.SQLException;

public class TypeInfoCacheGetPGTypeByOidTest extends TypeInfoCacheGetPGTypeBaseTest {
  @Parameterized.Parameters(name = "{0} (oid) → {1}")
  public static Iterable<Object[]> data() {
    return getPGTypeByOidParams();
  }

  private final PgTypeStruct type;
  private final String expectedNameString;
  private final PgTypeStruct arrayType;
  private final String expectedArrayNameString;

  public TypeInfoCacheGetPGTypeByOidTest(PgTypeStruct type, String expectedNameString,
      PgTypeStruct arrayType, String expectedArrayNameString) {
    this.type = type;
    this.expectedNameString = expectedNameString;
    this.arrayType = arrayType;
    this.expectedArrayNameString = expectedArrayNameString;
  }

  @Test
  public void testGetPGTypeByOid() throws SQLException {
    int oid = oid(type);
    String nameString = typeInfo.getPGType(oid);
    assertEquals(expectedNameString, nameString);
    int roundTripOid = typeInfo.getPGType(nameString);
    assertEquals("name returned by getPGType(oid) round-trips through getPGType(name)",
        type, type(roundTripOid));
  }

  @Test
  public void testGetPGTypeByArrayOid() throws SQLException {
    assumeSupportedType(conn, arrayType);
    int oid = oid(arrayType);
    String nameString = typeInfo.getPGType(oid);
    assertEquals(expectedArrayNameString, nameString);
    int roundTripOid = typeInfo.getPGType(nameString);
    assertEquals("name of array type returned by getPGType(oid) round-trips through getPGType(name)",
        arrayType, type(roundTripOid));
  }
}
