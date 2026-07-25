/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

/**
 * Verifies parser behavior on runtimes that do not provide {@code java.lang.management}
 * (e.g., Android ART). The "missing" state is modeled by a {@link LongSupplier} that returns
 * {@code -1}, matching what
 * {@link org.postgresql.util.internal.JvmHeapAccess JvmHeapAccess} would surface via the
 * production {@code NoClassDefFoundError} guard.
 */
public class PGPropertyMaxResultBufferParserRuntimeTest {

  private static final LongSupplier MANAGEMENT_FACTORY_UNAVAILABLE = () -> -1L;

  @Test
  void parsesUnsetValueWhenManagementFactoryIsUnavailable() throws PSQLException {
    assertEquals(-1,
        PGPropertyMaxResultBufferParser.parseProperty(null, MANAGEMENT_FACTORY_UNAVAILABLE));
  }

  @Test
  void parsesByteValueWhenManagementFactoryIsUnavailable() throws PSQLException {
    assertEquals(1000,
        PGPropertyMaxResultBufferParser.parseProperty("1K", MANAGEMENT_FACTORY_UNAVAILABLE));
  }

  @Test
  void rejectsPercentValueWhenManagementFactoryIsUnavailable() {
    assertThrows(PSQLException.class, () ->
        PGPropertyMaxResultBufferParser.parseProperty("10pct", MANAGEMENT_FACTORY_UNAVAILABLE));
  }
}
