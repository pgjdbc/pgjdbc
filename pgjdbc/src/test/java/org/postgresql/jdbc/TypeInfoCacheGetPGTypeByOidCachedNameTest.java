/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;
import static org.postgresql.jdbc.TypeInfoCacheTestParameters.getPGTypeByOidCachedNameParams;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeStruct;

import org.postgresql.core.TypeInfo;
import org.postgresql.test.TestUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Connection;
import java.sql.SQLException;

@RunWith(Parameterized.class)
public class TypeInfoCacheGetPGTypeByOidCachedNameTest extends TypeInfoCacheGetPGTypeBaseTest {

  private final PgTypeStruct type;
  private final String nameString;
  private final PgTypeStruct arrayType;
  private final String arrayNameString;
  private int oid;
  private String uncachedNameString;
  private int arrayOid;
  private String uncachedArrayNameString;

  private Connection myConn;
  private TypeInfo myTypeInfo;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    oid = oid(type);
    uncachedNameString = typeInfo.getPGType(oid);
    arrayOid = oid(arrayType);
    uncachedArrayNameString = typeInfo.getPGType(arrayOid);

    myConn = TestUtil.openDB();
    myTypeInfo = ((PgConnection) myConn).getTypeInfo();
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.closeDB(myConn);
    super.tearDown();
  }

  @Parameterized.Parameters(name = "{0}")
  public static Iterable<Object[]> data() {
    return getPGTypeByOidCachedNameParams();
  }

  public TypeInfoCacheGetPGTypeByOidCachedNameTest(PgTypeStruct type, String nameString,
      String arrayNameString) {
    this.type = type;
    this.nameString = nameString;
    this.arrayType = PgTypeStruct.createArrayType(type);
    this.arrayNameString = arrayNameString;
  }

  @Test
  public void testGetPGTypeByNameCached() throws Exception {
    myTypeInfo.getPGType(uncachedNameString);
    String cachedNameString = myTypeInfo.getPGType(oid);
    assertEquals("type name cached by getPGType(name) matches cached by getPGType(oid)",
        nameString, cachedNameString);
  }

  @Test
  public void testGetPGTypeByArrayNameCached() throws Exception {
    assumeSupportedType(conn, arrayType);
    myTypeInfo.getPGType(uncachedArrayNameString);
    String cachedNameString = myTypeInfo.getPGType(arrayOid);
    assertEquals("array type name cached by getPGType(name) matches that cached by getPGType(oid)",
        arrayNameString, cachedNameString);
  }

}
