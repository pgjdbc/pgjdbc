/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests the decoding of {@code numeric} type modifiers without a server. Each type modifier is the value that
 * PostgreSQL 17 stores in {@code pg_attribute.atttypmod} for the declared type.
 */
class TypeInfoCacheNumericTest {

  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "'numeric(10,0)', 655364, 0",
      "'numeric(10,2)', 655366, 2",
      "'numeric(2,5)', 131081, 5",
      "'numeric(1000,1000)', 65537004, 1000",
      "'numeric(10,-2)', 657410, -2",
      "'numeric(1,-1000)', 66588, -1000",
  })
  void scale(String type, int typmod, int expectedScale) {
    assertEquals(expectedScale, TypeInfoCache.numericScale(typmod), () -> "scale of " + type);
  }
}
