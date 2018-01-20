/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeStruct;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.assumeUserDefinedArrays;

import org.postgresql.core.Oid;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.TypeInfo;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;

/*
 This extends BaseTest4 instead of TypeInfoCachePGTypeBaseTest as it uses more than one custom type.
 PgTypeSet (used in TypeInfoCachePGTypeBaseTest) only creates a single type of custom type.
 */
@RunWith(Parameterized.class)
public class TypeInfoCacheGetJavaClassTest extends BaseTest4 {
  private TypeInfo typeInfo;

  private final PgTypeStruct pgType;
  private final int expectedSQLType;
  private final String expectedClassName;
  private int oid;
  private int arrayOid;

  private static final String CUSTOM_SCHEMA = "ns";
  private static final String DISTINCT_TYPE = "d";
  private static final String STRUCT_TYPE = "s";
  private static final String VARCHAR_TYPE = "v";

  @Override
  public void setUp() throws Exception {
    super.setUp();
    typeInfo = ((PgConnection) con).getTypeInfo();

    if (CUSTOM_SCHEMA.equals(pgType.nspname)) {
      TestUtil.createSchema(con, pgType.nspname);
      if (DISTINCT_TYPE.equals(pgType.typname)) {
        TestUtil.createDomain(con, pgType.name(), "int");
      } else if (STRUCT_TYPE.equals(pgType.typname)) {
        TestUtil.createCompositeType(con, pgType.name(), "v timestamptz");
      } else if (VARCHAR_TYPE.equals(pgType.typname)) {
        assumeTrue("enum requires PostgreSQL 8.3 or later",
            expectedSQLType != Types.VARCHAR || TestUtil.haveMinimumServerVersion(con,
                ServerVersion.v8_3));
        TestUtil.createEnumType(con, pgType.name(), "'black'");
      }
    }

    oid = Oid.UNSPECIFIED;
    arrayOid = Oid.UNSPECIFIED;

    PreparedStatement stmt = con.prepareStatement(
        "SELECT t.oid, COALESCE(arr.oid, 0) "
            + " FROM pg_catalog.pg_type AS t"
            + " JOIN pg_catalog.pg_namespace AS n ON n.oid = t.typnamespace"
            + " LEFT JOIN pg_catalog.pg_type AS arr"
            + " ON (t.oid, 'array_in'::regproc) = (arr.typelem, arr.typinput)"
            + " WHERE (nspname, t.typname) = (?, ?)");
    stmt.setString(1, pgType.nspname);
    stmt.setString(2, pgType.typname);
    ResultSet rs = stmt.executeQuery();
    if (rs.next()) {
      oid = (int) rs.getLong(1);
      arrayOid = (int) rs.getLong(2);
    }
  }

  @Parameterized.Parameters(name = "{0} → {2}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(
        new Object[]{new PgTypeStruct("pg_catalog", "int2"), Types.SMALLINT, "java.lang.Integer"},
        new Object[]{new PgTypeStruct("pg_catalog", "int4"), Types.INTEGER, "java.lang.Integer"},
        new Object[]{new PgTypeStruct("pg_catalog", "numeric"), Types.NUMERIC, "java.math.BigDecimal"},
        new Object[]{new PgTypeStruct(CUSTOM_SCHEMA, DISTINCT_TYPE), Types.DISTINCT, null},
        new Object[]{new PgTypeStruct(CUSTOM_SCHEMA, STRUCT_TYPE), Types.STRUCT, null},
        new Object[]{new PgTypeStruct(CUSTOM_SCHEMA, VARCHAR_TYPE), Types.VARCHAR, null});
  }

  public TypeInfoCacheGetJavaClassTest(PgTypeStruct pgType, int expectedSQLType,
      String expectedClassName) {
    this.pgType = pgType;
    this.expectedSQLType = expectedSQLType;
    this.expectedClassName = expectedClassName;
  }

  @Test
  public void testGetJavaClass() throws SQLException {
    assertEquals("class name", expectedClassName, typeInfo.getJavaClass(oid));
    assertEquals("cached class name", expectedClassName, typeInfo.getJavaClass(oid));
  }

  @Test
  public void testGetJavaClassForArrays() throws SQLException {
    assumeUserDefinedArrays(con);
    assumeTrue("arrays of domains (represented by Types.DISTINCT) aren't supported by PostgreSQL",
        expectedSQLType != Types.DISTINCT);
    assertEquals("array class name", "java.sql.Array", typeInfo.getJavaClass(arrayOid));
    assertEquals("cached array class name", "java.sql.Array", typeInfo.getJavaClass(arrayOid));
  }
}
