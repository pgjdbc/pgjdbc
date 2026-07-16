/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.postgresql.core.Field;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Guards the invariant that every result column of the {@code pg_type} lookup resolves to a built-in
 * type from {@code BASE_TYPES}.
 *
 * <p>{@link TypeInfoCache} loads type metadata by decoding this query's own result. Under
 * {@code binaryTransferEnable=*} a column whose type is not built-in comes back in binary, and
 * reading it re-enters the type cache to resolve that column's own type — which is not loaded yet —
 * recursing until the stack overflows. The {@code regproc} columns ({@code typsend},
 * {@code typreceive}) are cast to {@code text} for exactly this reason. This test fails if a future
 * edit adds a non-built-in column to {@code PG_TYPE_FIELDS} or drops that cast.</p>
 */
public class PgTypeFieldsBaseColumnGuardTest {

  @Test
  void everyPgTypeFieldColumnIsABaseType() throws SQLException {
    // The column type OIDs are the same in text and binary, so a plain connection is enough to
    // inspect them; the recursion this guards against only manifests under binaryTransferEnable=*.
    String sql = "SELECT " + TypeInfoCache.PG_TYPE_FIELDS
        + " FROM " + TypeInfoCache.PG_TYPE_TABLE
        + " WHERE t.oid = 'int4'::regtype";
    try (Connection con = TestUtil.openDB();
         Statement st = con.createStatement();
         ResultSet rs = st.executeQuery(sql)) {
      PgResultSetMetaData md = (PgResultSetMetaData) rs.getMetaData();
      int columns = md.getColumnCount();
      for (int col = 1; col <= columns; col++) {
        Field field = md.getField(col);
        int oid = field.getOID();
        assertNotNull(TypeInfoCache.getDefaultType(oid),
            "Column '" + md.getColumnLabel(col) + "' (oid " + oid + ") in PG_TYPE_FIELDS is not a "
                + "built-in type. Under binaryTransferEnable=* the type cache recurses while decoding "
                + "it. Cast the column to a built-in type (e.g. ::text) in PG_TYPE_FIELDS.");
      }
    }
  }
}
