/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PSQLStateTest {

  @Test
  void fromCode() {
    assertEquals(PSQLState.UNIQUE_VIOLATION, PSQLState.fromCodeOrNull("23505"));
    assertNull(PSQLState.fromCode(null));
  }

  @Test
  void isConnectionError() {
    assertTrue(PSQLState.isConnectionError("08006")); // connection_failure
    assertFalse(PSQLState.isConnectionError("23503")); // foreign_key_violation
  }
}
