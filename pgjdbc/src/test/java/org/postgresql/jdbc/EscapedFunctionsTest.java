/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

@SuppressWarnings("deprecation")
class EscapedFunctionsTest {
  @Test
  void subtractedArgumentInParentheses() throws Exception {
    assertEquals("substring(c from (length(c)+1-(-1)))",
        EscapedFunctions.sqlright(Arrays.asList("c", "-1")));
    assertEquals("extract( day from ((d)-(-1)))",
        EscapedFunctions.sqltimestampdiff(Arrays.asList("SQL_TSI_DAY", "-1", "d")));
  }
}
