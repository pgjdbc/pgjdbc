/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;
import static org.postgresql.jdbc.TypeInfoCacheTestParameters.installedTypes;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeStruct;

import org.junit.Test;
import org.junit.runners.Parameterized;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;

public class TypeInfoCacheGetPGArrayElementTest extends TypeInfoCacheGetPGTypeBaseTest {
  @Parameterized.Parameters(name = "{0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> params = new ArrayList<Object[]>();
    for (PgTypeStruct elementType : installedTypes) {
      PgTypeStruct arrayType = PgTypeStruct.createArrayType(elementType);
      params.add(new Object[]{arrayType, elementType});
    }
    return params;
  }

  private final PgTypeStruct arrayType;
  private final int arrayOid;
  private final PgTypeStruct elementType;

  public TypeInfoCacheGetPGArrayElementTest(PgTypeStruct arrayType, PgTypeStruct elementType)
      throws SQLException {
    this.arrayType = arrayType;
    this.arrayOid = oid(arrayType);
    this.elementType = elementType;
  }

  @Test
  public void testGetPGArrayElementType() throws SQLException {
    assumeSupportedType(conn, arrayType);
    int elementOid = typeInfo.getPGArrayElement(arrayOid);
    assertEquals("type cached by getPGArrayElement matches element type of given array",
        elementType, type(elementOid));
  }
}
