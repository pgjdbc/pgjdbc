/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGConnection;
import org.postgresql.fastpath.Fastpath;
import org.postgresql.fastpath.FastpathArg;
import org.postgresql.util.ByteStreamWriter;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

class FastpathCallLengthTest extends BaseTest4 {
  private static ByteStreamWriter writerOfLength(int length) {
    return new ByteStreamWriter() {
      @Override
      public int getLength() {
        return length;
      }

      @Override
      public void writeTo(ByteStreamTarget target) {
        fail("no argument must be written");
      }
    };
  }

  @Test
  void callWhoseLengthWrapsAroundIntIsRefused() throws SQLException {
    Fastpath fastpath = con.unwrap(PGConnection.class).getFastpathAPI();
    FastpathArg[] args = {
        FastpathArg.of(writerOfLength(Integer.MAX_VALUE)),
        FastpathArg.of(writerOfLength(Integer.MAX_VALUE)),
    };
    SQLException e = assertThrows(SQLException.class, () -> fastpath.fastpath(1, args));
    assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState());

    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT 1")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
    }
  }
}
