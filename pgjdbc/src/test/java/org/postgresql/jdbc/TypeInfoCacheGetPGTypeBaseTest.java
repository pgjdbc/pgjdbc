/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.jdbc.TypeInfoCacheTestParameters.installedTypes;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeStruct;

import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeSet;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Many TypeInfoCache tests rely on knowledge of a set of user-defined and pg_catalog types.
 * This class creates an instance of TypeInfoCacheTestUtil.PgTypeSet with the list of
 * TypeInfoCacheTestParameters.installedTypes and makes those types easily available via oid() and
 * type() methods which wrap the PgTypeSet instance. It also uses beforeClass and afterClass to set
 * up and tear down these types once per class rather than per test.
 */
@RunWith(Parameterized.class)
public class TypeInfoCacheGetPGTypeBaseTest {
  private static PgTypeSet typeSet;

  @Rule
  public final ExpectedException exception = ExpectedException.none();

  @BeforeClass
  public static void beforeClass() throws Exception {
    Connection conn = TestUtil.openDB();
    typeSet = PgTypeSet.createAndInstall(installedTypes, conn);
    TestUtil.closeDB(conn);
  }

  @AfterClass
  public static void afterClass() throws Exception {
    Connection conn = TestUtil.openDB();
    typeSet.uninstall(conn);
    TestUtil.closeDB(conn);
  }

  Connection conn;
  TypeInfo typeInfo;

  @Before
  public void setUp() throws Exception {
    conn = TestUtil.openDB();
    typeInfo = ((PgConnection) conn).getTypeInfo();
  }

  @After
  public void tearDown() throws SQLException {
    TestUtil.closeDB(conn);
  }

  public TypeInfoCacheGetPGTypeBaseTest() {
  }

  static void assumeSupportedType(Connection conn, PgTypeStruct type) throws SQLException {
    typeSet.assumeSupportedType(conn, type);
  }

  static int oid(PgTypeStruct type) {
    return typeSet.oid(type);
  }

  static PgTypeStruct type(int oid) {
    return typeSet.type(oid);
  }

}
