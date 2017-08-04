/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.assertSQLType;

import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeSet;
import org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeStruct;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

@RunWith(Parameterized.class)
public class TypeInfoCacheGetPGTypeNamesWithSQLTypesTest extends BaseTest4 {

  private TypeInfo typeInfo;
  private PgTypeSet typeSet;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    typeInfo = ((PgConnection) con).getTypeInfo();
    typeSet = PgTypeSet.createAndInstall(types, con);
    sqlType = typeSet.userDefinedTypeSqlType(con);
  }

  @Override
  public void tearDown() throws SQLException {
    typeSet.uninstall(con);
    super.tearDown();
  }

  @Parameterized.Parameters(name = "{0} ; Types: {1}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> cases = new ArrayList<Object[]>();
    //noinspection ArraysAsListWithZeroOrOneArgument
    List<PgTypeStruct> types = Arrays.asList(new PgTypeStruct("ns", "type"));
    cases.add(new Object[]{"ns.type", types});
    return cases;
  }

  private final List<PgTypeStruct> types;
  private final String nameString;
  private int sqlType;

  public TypeInfoCacheGetPGTypeNamesWithSQLTypesTest(String nameString,
      List<PgTypeStruct> types) throws SQLException {
    this.nameString = nameString;
    this.types = types;
  }

  @Test
  /*
  This is not an expansive test. It only confirms that the iterator contains expected data,
  not that the iterator is implemented correctly.
   */
  public void testGetPGTypeNamesForUserDefinedType() throws SQLException {
    boolean isFound = false;

    for (Iterator<String> i = typeInfo.getPGTypeNamesWithSQLTypes(); i.hasNext(); ) {
      String fetchedNameString = i.next();
      if (fetchedNameString.equals(nameString)) {
        isFound = true;
        break;
      }
    }

    assertFalse("not found before loaded into cache", isFound);

    // The getSQLType call will load the type into cache.
    assertSQLType("have expected SQL Type", sqlType, typeInfo.getSQLType(nameString));

    for (Iterator<String> i = typeInfo.getPGTypeNamesWithSQLTypes(); i.hasNext(); ) {
      String fetchedNameString = i.next();
      if (fetchedNameString.equals(nameString)) {
        isFound = true;
        break;
      }
    }
    assertTrue("found after loaded into cache", isFound);
  }
}
