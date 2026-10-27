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

  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "'numeric(10,0)', 655364, 10",
      "'numeric(2,5)', 131081, 2",
      "'numeric(1000,1000)', 65537004, 1000",
      "'numeric(10,-2)', 657410, 10",
      "'numeric(1,-1000)', 66588, 1",
  })
  void precision(String type, int typmod, int expectedPrecision) {
    assertEquals(expectedPrecision, TypeInfoCache.numericPrecision(typmod), () -> "precision of " + type);
  }

  /**
   * The expected length is that of the longest value the type holds, as PostgreSQL 17 prints it.
   */
  @ParameterizedTest(name = "{0} holds {2}")
  @CsvSource({
      "'numeric(10,0)', 655364, -9999999999",
      "'numeric(10,1)', 655365, -999999999.9",
      "'numeric(10,2)', 655366, -99999999.99",
      "'numeric(6,4)', 393224, -99.9999",
      "'numeric(2,2)', 131078, -0.99",
      "'numeric(2,5)', 131081, -0.00099",
      "'numeric(3,-1)', 198659, -9990",
      "'numeric(2,-2)', 133122, -9900",
      "'numeric(10,-2)', 657410, -999999999900",
  })
  void displaySize(String type, int typmod, String longestValue) {
    assertEquals(longestValue.length(), TypeInfoCache.numericDisplaySize(typmod),
        () -> "display size of " + type);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "'numeric(1000,1000)', 65537004, 1003",
      "'numeric(1,-1000)', 66588, 1002",
  })
  void displaySizeAtTheScaleLimits(String type, int typmod, int expectedSize) {
    assertEquals(expectedSize, TypeInfoCache.numericDisplaySize(typmod), () -> "display size of " + type);
  }
}
