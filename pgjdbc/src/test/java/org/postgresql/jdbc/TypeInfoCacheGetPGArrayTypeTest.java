/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;
import static org.postgresql.jdbc.TypeInfoCacheTestParameters.getPGArrayTypeParseableTestParams;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeStruct;

import org.junit.Test;
import org.junit.runners.Parameterized;

import java.sql.SQLException;

public class TypeInfoCacheGetPGArrayTypeTest extends TypeInfoCacheGetPGTypeBaseTest {

  @Parameterized.Parameters(name = "{0} → {1} (oid)")
  public static Iterable<Object[]> data() {
    return getPGArrayTypeParseableTestParams();
  }

  private final String nameString;
  private final PgTypeStruct type;

  public TypeInfoCacheGetPGArrayTypeTest(String nameString, PgTypeStruct type) {
    this.nameString = nameString;
    this.type = type;
  }

  @Test
  public void testGetPGArrayType() throws SQLException {
    assumeSupportedType(conn, type);
    int oid = typeInfo.getPGArrayType(nameString);
    assertEquals(type, type(oid));
  }
}
