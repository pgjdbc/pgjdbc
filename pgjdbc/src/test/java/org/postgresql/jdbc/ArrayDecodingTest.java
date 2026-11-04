/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;

class ArrayDecodingTest {
  private static BaseConnection con;

  @BeforeAll
  static void openDB() throws SQLException {
    con = TestUtil.openDB().unwrap(BaseConnection.class);
  }

  @AfterAll
  static void closeDB() throws SQLException {
    TestUtil.closeDB(con);
  }

  private static Object decode(String text) throws SQLException {
    return ArrayDecoding.readStringArray(0, 0, Oid.INT4_ARRAY,
        ArrayDecoding.buildArrayList(text, ','), con);
  }

  /** At each level only the first of 40 entries is filled. */
  private static String sparse(int depth) {
    if (depth == 0) {
      return "1";
    }
    StringBuilder sb = new StringBuilder("{").append(sparse(depth - 1));
    for (int i = 1; i < 40; i++) {
      sb.append(",{}");
    }
    return sb.append('}').toString();
  }

  @Test
  void rectangularArraysAreDecoded() throws SQLException {
    assertEquals("[[1, 2], [3, 4]]", Arrays.deepToString((Object[]) decode("{{1,2},{3,4}}")));
    assertEquals("[[], []]", Arrays.deepToString((Object[]) decode("{{},{}}")));
    assertEquals("[[[1], [2]]]", Arrays.deepToString((Object[]) decode("{{{1},{2}}}")));
  }

  @Test
  void sparseNestedArrayIsRefusedBeforeAllocation() {
    String text = sparse(6);
    PSQLException e = assertThrows(PSQLException.class, () -> decode(text));
    assertEquals(PSQLState.DATA_ERROR.getState(), e.getSQLState());
  }
}
