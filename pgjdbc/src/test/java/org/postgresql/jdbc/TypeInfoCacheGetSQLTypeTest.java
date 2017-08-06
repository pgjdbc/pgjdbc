/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assume.assumeTrue;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeStruct;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.assertSQLType;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.assumeUserDefinedArrays;

import org.postgresql.core.Oid;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.TypeInfoCacheTestUtil.SQLType;
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
public class TypeInfoCacheGetSQLTypeTest extends BaseTest4 {
  private TypeInfo typeInfo;

  private final PgTypeStruct type;
  private final SQLType expectedSQLType;
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
    oid = Oid.UNSPECIFIED;
    arrayOid = Oid.UNSPECIFIED;

    if (CUSTOM_SCHEMA.equals(type.nspname)) {
      TestUtil.createSchema(con, type.nspname);
      if (DISTINCT_TYPE.equals(type.typname)) {
        TestUtil.createDomain(con, type.name(), "int");
      } else if (STRUCT_TYPE.equals(type.typname)) {
        TestUtil.createCompositeType(con, type.name(), "v timestamptz");
      } else if (VARCHAR_TYPE.equals(type.typname)) {
        assumeTrue("enum requires PostgreSQL 8.3 or later",
            expectedSQLType.type != Types.VARCHAR || TestUtil.haveMinimumServerVersion(con,
                ServerVersion.v8_3));
        TestUtil.createEnumType(con, type.name(), "'black'");
      }
    }

    PreparedStatement stmt = con.prepareStatement(
        "SELECT t.oid, COALESCE(arr.oid, 0) "
            + " FROM pg_catalog.pg_type AS t"
            + " JOIN pg_catalog.pg_namespace AS n ON n.oid = t.typnamespace"
            + " LEFT JOIN pg_catalog.pg_type AS arr"
            + " ON (t.oid, 'array_in'::regproc) = (arr.typelem, arr.typinput)"
            + " WHERE (nspname, t.typname) = (?, ?)");
    stmt.setString(1, type.nspname);
    stmt.setString(2, type.typname);
    ResultSet rs = stmt.executeQuery();
    if (rs.next()) {
      oid = (int) rs.getLong(1);
      arrayOid = (int) rs.getLong(2);
    }
  }

  @Parameterized.Parameters(name = "{0} → {1}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(
        new Object[]{new PgTypeStruct("pg_catalog", "int2"), SQLType.SMALLINT},
        new Object[]{new PgTypeStruct("pg_catalog", "int4"), SQLType.INTEGER},
        new Object[]{new PgTypeStruct("pg_catalog", "numeric"), SQLType.NUMERIC},
        new Object[]{new PgTypeStruct(CUSTOM_SCHEMA, DISTINCT_TYPE), SQLType.DISTINCT},
        new Object[]{new PgTypeStruct(CUSTOM_SCHEMA, STRUCT_TYPE), SQLType.STRUCT},
        new Object[]{new PgTypeStruct(CUSTOM_SCHEMA, VARCHAR_TYPE), SQLType.VARCHAR});
  }

  public TypeInfoCacheGetSQLTypeTest(PgTypeStruct type, SQLType expectedSQLType) {
    this.type = type;
    this.expectedSQLType = expectedSQLType;
  }

  @Test
  public void testGetSQLType() throws SQLException {
    assertSQLType("oid", expectedSQLType.type, typeInfo.getSQLType(oid));
    assertSQLType("cached oid", expectedSQLType.type, typeInfo.getSQLType(oid));
    assertSQLType("nameString", expectedSQLType.type, typeInfo.getSQLType(type.name()));
    assertSQLType("cached nameString", expectedSQLType.type,
        typeInfo.getSQLType(type.name()));
  }

  @Test
  public void testGetSQLTypeForArrays() throws SQLException {
    assumeUserDefinedArrays(con);
    assumeTrue("arrays of domains (represented by Types.DISTINCT) aren't supported by PostgreSQL",
        expectedSQLType.type != Types.DISTINCT);
    assertSQLType("array oid", Types.ARRAY, typeInfo.getSQLType(arrayOid));
    assertSQLType("cached array oid", Types.ARRAY, typeInfo.getSQLType(arrayOid));
    String arrayTypeName = type.name() + "[]";
    assertSQLType("nameString", Types.ARRAY, typeInfo.getSQLType(arrayTypeName));
    assertSQLType("cached nameString", Types.ARRAY, typeInfo.getSQLType(arrayTypeName));
  }
}
