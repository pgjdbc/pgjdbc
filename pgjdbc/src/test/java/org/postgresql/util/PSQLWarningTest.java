/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PSQLWarningTest {
  @Test
  void serverWarningHasNoStackTrace() {
    PSQLWarning warning = new PSQLWarning(new ServerErrorMessage("SNOTICE\0C00000\0Mnotice\0\0"));
    assertEquals(0, warning.getStackTrace().length);
    assertEquals("notice", warning.getMessage());
    assertEquals("00000", warning.getSQLState());
  }
}
