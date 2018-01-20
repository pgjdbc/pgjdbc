/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;

import org.postgresql.core.TypeInfo;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class TypeInfoCacheGetTypeForAliasTest extends BaseTest4 {

  private TypeInfo typeInfo;

  private final String expectedType;
  private final String alias;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    typeInfo = ((PgConnection) con).getTypeInfo();
  }

  @Parameterized.Parameters(name = "{0} → {1}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> aliasing = Arrays.asList(
        new Object[]{"int2", new String[]{"smallint"}},
        new Object[]{"int4", new String[]{"integer", "int"}},
        new Object[]{"float8", new String[]{"float"}},
        new Object[]{"bool", new String[]{"boolean"}},
        new Object[]{"numeric", new String[]{"decimal"}});

    Collection<Object[]> params = new ArrayList<Object[]>();
    for (Object[] a : aliasing) {
      String base = (String) a[0];
      String[] aliases = (String[]) a[1];
      for (String alias : aliases) {
        params.add(new Object[]{alias.toUpperCase(), base});
        params.add(new Object[]{alias, base});
      }
    }
    return params;
  }


  public TypeInfoCacheGetTypeForAliasTest(String alias, String expectedType) {
    this.alias = alias;
    this.expectedType = expectedType;
  }

  @Test
  public void testGetTypeForAlias() {
    assertEquals(expectedType, typeInfo.getTypeForAlias(alias));
  }

}
