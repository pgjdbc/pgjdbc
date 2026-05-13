/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class PGPropertyMaxResultBufferParserRuntimeTest {

  private static final String MISSING_MANAGEMENT_FACTORY_CLASS_NAME =
      "org.postgresql.util.MissingManagementFactory";

  @Test
  void parsesUnsetValueWhenManagementFactoryIsUnavailable() throws PSQLException {
    assertEquals(-1,
        PGPropertyMaxResultBufferParser.parseProperty(null, MISSING_MANAGEMENT_FACTORY_CLASS_NAME));
  }

  @Test
  void parsesByteValueWhenManagementFactoryIsUnavailable() throws PSQLException {
    assertEquals(1000,
        PGPropertyMaxResultBufferParser.parseProperty("1K", MISSING_MANAGEMENT_FACTORY_CLASS_NAME));
  }

  @Test
  void rejectsPercentValueWhenManagementFactoryIsUnavailable() {
    assertThrows(PSQLException.class, () ->
        PGPropertyMaxResultBufferParser.parseProperty("10pct", MISSING_MANAGEMENT_FACTORY_CLASS_NAME));
  }

  @Test
  void reportsUnavailableManagementFactory() throws PSQLException {
    assertEquals(-1,
        PGPropertyMaxResultBufferParser.getMaxHeapMemory(MISSING_MANAGEMENT_FACTORY_CLASS_NAME));
  }
}
