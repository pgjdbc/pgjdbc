/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;

import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeSet;
import org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeStruct;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

@RunWith(Parameterized.class)
public class TypeInfoCacheGetArrayDelimiterTest {
  @Rule
  public final ExpectedException exception = ExpectedException.none();

  private static PgTypeSet typeSet;

  private Connection conn;
  private TypeInfo typeInfo;

  @BeforeClass
  public static void beforeClass() throws Exception {
    Connection conn = TestUtil.openDB();
    typeSet = PgTypeSet.createAndInstall(types, conn);
  }

  @AfterClass
  public static void afterClass() throws Exception {
    Connection conn = TestUtil.openDB();
    typeSet.uninstall(conn);
    TestUtil.closeDB(conn);
  }

  @Before
  public void setUp() throws Exception {
    conn = TestUtil.openDB();
    typeInfo = ((PgConnection) conn).getTypeInfo();
  }

  @After
  public void tearDown() throws SQLException {
    TestUtil.closeDB(conn);
  }

  private final PgTypeStruct type;
  private final PgTypeStruct arrayType;
  private final Character delimiter;

  private static final Character DEFAULT_DELIMITER = ',';

  private static final List<PgTypeStruct> types = Arrays.asList(
      new PgTypeStruct("ns", "type"),
      new PgTypeStruct("public", "type"),
      new PgTypeStruct("pg_catalog", "box"),
      new PgTypeStruct("pg_catalog", "int2"),
      new PgTypeStruct("pg_catalog", "int4"),
      new PgTypeStruct("pg_catalog", "text"));

  @Parameterized.Parameters(name = "{0} → {1}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(
        new Object[]{new PgTypeStruct("pg_catalog", "int2"), DEFAULT_DELIMITER},
        new Object[]{new PgTypeStruct("pg_catalog", "text"), DEFAULT_DELIMITER},
        new Object[]{new PgTypeStruct("pg_catalog", "box"), ';'},
        new Object[]{new PgTypeStruct("ns", "type"), DEFAULT_DELIMITER},
        new Object[]{new PgTypeStruct("public", "type"), DEFAULT_DELIMITER});
  }

  public TypeInfoCacheGetArrayDelimiterTest(PgTypeStruct type, Character delimiter) {
    this.type = type;
    this.arrayType = PgTypeStruct.createArrayType(type);
    this.delimiter = delimiter;
  }

  @Test
  public void testGetArrayDelimiterByElementOidThrows() throws SQLException {
    int oid = typeSet.oid(type);
    exception.expect(PSQLException.class);
    typeInfo.getArrayDelimiter(oid);
  }

  @Test
  public void testGetArrayDelimiterByArrayOid() throws SQLException {
    typeSet.assumeSupportedType(conn, arrayType);
    int oid = typeSet.oid(arrayType);
    assertEquals(String.valueOf(delimiter), String.valueOf(typeInfo.getArrayDelimiter(oid)));
  }
}
