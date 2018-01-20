/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;
import static org.postgresql.jdbc.TypeInfoCacheTestParameters.getPGTypeParseableNameParams;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeStruct;

import org.postgresql.core.Oid;

import org.junit.Test;
import org.junit.runners.Parameterized;

import java.sql.SQLException;

public class TypeInfoCacheGetPGTypeByNameTest extends TypeInfoCacheGetPGTypeBaseTest {

  @Parameterized.Parameters(name = "{0} → {1} (oid)")
  public static Iterable<Object[]> data() {
    return getPGTypeParseableNameParams();
  }

  private final String nameString;
  private final PgTypeStruct type;

  public TypeInfoCacheGetPGTypeByNameTest(String nameString, PgTypeStruct getPGTypeType) {
    this.nameString = nameString;
    this.type = getPGTypeType;
  }

  @Test
  public void testGetPGTypeByName() throws SQLException {
    assumeSupportedType(conn, type);
    int oid = typeInfo.getPGType(nameString);
    PgTypeStruct expectedType = type.hasSearchPathException() ? type.searchPathException : type;
    assertEquals(expectedType, type(oid));
    String roundTripNameString = (oid == Oid.UNSPECIFIED) ? null : typeInfo.getPGType(oid);
    int roundTripOid = typeInfo.getPGType(roundTripNameString);
    assertEquals("round trip (oid)", expectedType, type(roundTripOid));
  }
}
