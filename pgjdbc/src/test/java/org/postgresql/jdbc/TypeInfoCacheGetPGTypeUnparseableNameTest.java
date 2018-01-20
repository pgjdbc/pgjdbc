/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.jdbc.TypeInfoCacheTestParameters.getPGTypeUnParseableNameParams;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runners.Parameterized;

import java.sql.SQLException;

public class TypeInfoCacheGetPGTypeUnparseableNameTest extends TypeInfoCacheGetPGTypeBaseTest {
  @Rule
  public final ExpectedException exception = ExpectedException.none();

  @Parameterized.Parameters(name = "{0}")
  public static Iterable<Object[]> data() {
    return getPGTypeUnParseableNameParams();
  }

  private final String nameString;

  public TypeInfoCacheGetPGTypeUnparseableNameTest(String nameString) {
    this.nameString = nameString;
  }

  @Test
  public void testGetPgTypeThrowsOnUnparseableNames() throws SQLException {
    exception.expect(Exception.class);
    typeInfo.getPGType(nameString);
  }
}
