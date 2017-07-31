/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;
import static org.postgresql.jdbc.TypeInfoCacheTestParameters.badRoundTripTypes;
import static org.postgresql.jdbc.TypeInfoCacheTestParameters.installedTypes;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeStruct;

import org.junit.Test;
import org.junit.runners.Parameterized;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;

public class TypeInfoCacheGetPGArrayTypeCachedNameTest extends TypeInfoCacheGetPGTypeBaseTest {

  private int oid;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    oid = oid(type);
  }

  @Parameterized.Parameters(name = "{0} → {1} (oid)")
  public static Iterable<Object[]> data() {
    Collection<Object[]> params = new ArrayList<Object[]>();
    for (PgTypeStruct type : installedTypes) {
      params.add(new Object[]{type, PgTypeStruct.createArrayType(type)});
    }
    return params;
  }

  private final PgTypeStruct type;
  private final PgTypeStruct arrayType;

  public TypeInfoCacheGetPGArrayTypeCachedNameTest(PgTypeStruct type, PgTypeStruct arrayType) {
    this.type = type;
    this.arrayType = arrayType;
  }

  @Test
  public void testGetPGTypeByCachedArrayName() throws SQLException {
    assumeSupportedType(conn, arrayType);
    String nameString = typeInfo.getPGType(oid);
    int arrayOid = typeInfo.getPGArrayType(nameString);
    PgTypeStruct expectedArrayType = (badRoundTripTypes.keySet().contains(type))
        ? PgTypeStruct.createArrayType(badRoundTripTypes.get(type)) : arrayType;
    assertEquals("type cached by getPGArrayType matches the array type of the given element type",
        expectedArrayType, type(arrayOid));
  }
}
