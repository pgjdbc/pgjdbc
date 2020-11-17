/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class JdbcBlackHole {
  public static void close(@Nullable Connection con) {
    try {
      if (con != null) {
        con.close();
      }
    } catch (SQLException e) {
      /* ignore for now */
    }
  }

  public static void close(@Nullable Statement s) {
    try {
      if (s != null) {
        s.close();
      }
    } catch (SQLException e) {
      /* ignore for now */
    }
  }

  public static void close(@Nullable ResultSet rs) {
    try {
      if (rs != null) {
        rs.close();
      }
    } catch (SQLException e) {
      /* ignore for now */
    }
  }
}
