/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

class ColumnPrivilegesTest {

  /**
   * A table-level grant applies to every column, while a column-level grant applies only to the
   * named columns. {@code getColumnPrivileges} must report the union of the two for each column.
   *
   * <p>Regression test for <a
   * href="https://github.com/pgjdbc/pgjdbc/discussions/4265">discussion #4265</a>: the table-level
   * {@code SELECT} granted to {@code one} used to surface on a single column (and only where that
   * column had no column-level {@code SELECT} to overwrite it) instead of on every column.</p>
   */
  @Test
  void mergesTableAndColumnLevelGrants() throws Exception {
    try (Connection con = TestUtil.openPrivilegedDB();
         Statement st = con.createStatement()) {
      try {
        st.execute("drop table if exists public.column_grant_test");
        st.execute("drop role if exists column_grant_one");
        st.execute("drop role if exists column_grant_two");
        st.execute("create user column_grant_one");
        st.execute("create user column_grant_two");
        st.execute("create table public.column_grant_test (c1 int, c2 int, c3 int)");
        // table-level SELECT: applies to every column
        st.execute("grant select on column_grant_test to column_grant_one");
        // column-level grants: apply only to the named columns
        st.execute("grant select (c1, c2) on column_grant_test to column_grant_two");
        st.execute("grant update (c1, c3) on column_grant_test to column_grant_two");

        DatabaseMetaData md = con.getMetaData();
        String owner = md.getUserName();

        Set<String> actual = new TreeSet<>();
        try (ResultSet rs =
                 md.getColumnPrivileges(null, "public", "column_grant_test", null)) {
          while (rs.next()) {
            String grantee = rs.getString("GRANTEE");
            // The owner holds every privilege on every column; ignore those rows so the
            // assertion only covers the grants made by this test.
            if (owner.equals(grantee)) {
              continue;
            }
            actual.add(rs.getString("PRIVILEGE") + " " + grantee + " " + rs.getString("COLUMN_NAME"));
          }
        }

        Set<String> expected = new LinkedHashSet<>();
        // table-level SELECT for column_grant_one is visible on every column
        expected.add("SELECT column_grant_one c1");
        expected.add("SELECT column_grant_one c2");
        expected.add("SELECT column_grant_one c3");
        // column-level SELECT for column_grant_two on c1, c2
        expected.add("SELECT column_grant_two c1");
        expected.add("SELECT column_grant_two c2");
        // column-level UPDATE for column_grant_two on c1, c3
        expected.add("UPDATE column_grant_two c1");
        expected.add("UPDATE column_grant_two c3");

        assertEquals(new TreeSet<>(expected), actual,
            "getColumnPrivileges should report the union of table-level and column-level grants");
      } finally {
        st.execute("drop table if exists public.column_grant_test");
        st.execute("drop role if exists column_grant_one");
        st.execute("drop role if exists column_grant_two");
      }
    }
  }

  /**
   * A privilege held by the same role at both the table level and the column level produces
   * identical grant records, which must be reported once per column rather than duplicated. This
   * exercises the de-duplication path of the table/column ACL merge, which the disjoint grants in
   * {@link #mergesTableAndColumnLevelGrants()} never reach.
   */
  @Test
  void deduplicatesGrantsHeldAtBothLevels() throws Exception {
    try (Connection con = TestUtil.openPrivilegedDB();
         Statement st = con.createStatement()) {
      try {
        st.execute("drop table if exists public.column_grant_dup_test");
        st.execute("drop role if exists column_grant_dup");
        st.execute("create user column_grant_dup");
        st.execute("create table public.column_grant_dup_test (c1 int, c2 int)");
        // The same privilege for the same role at both levels yields identical grant records.
        st.execute("grant select on column_grant_dup_test to column_grant_dup");
        st.execute("grant select (c1) on column_grant_dup_test to column_grant_dup");

        DatabaseMetaData md = con.getMetaData();
        String owner = md.getUserName();

        List<String> rows = new ArrayList<>();
        try (ResultSet rs =
                 md.getColumnPrivileges(null, "public", "column_grant_dup_test", null)) {
          while (rs.next()) {
            String grantee = rs.getString("GRANTEE");
            if (owner.equals(grantee)) {
              continue;
            }
            rows.add(rs.getString("PRIVILEGE") + " " + grantee + " " + rs.getString("COLUMN_NAME"));
          }
        }

        // c1 carries the grant at both levels; without de-duplication it would appear twice.
        assertEquals(1, Collections.frequency(rows, "SELECT column_grant_dup c1"),
            "the table-level and column-level SELECT for the same role must merge into one row");
        // c2 has no column-level grant, so the table-level grant appears once.
        assertEquals(1, Collections.frequency(rows, "SELECT column_grant_dup c2"),
            "the table-level SELECT must still appear once on a column without a column-level grant");
      } finally {
        st.execute("drop table if exists public.column_grant_dup_test");
        st.execute("drop role if exists column_grant_dup");
      }
    }
  }

  /**
   * A privilege held by nobody at the table level but granted at the column level must still be
   * reported for that column. Revoking the owner's table-level {@code INSERT} leaves a column-level
   * {@code INSERT} as the only holder of that privilege, so the merge has to add a fresh
   * per-privilege entry rather than extend an existing one.
   */
  @Test
  void reportsColumnLevelPrivilegeNotHeldAtTableLevel() throws Exception {
    try (Connection con = TestUtil.openPrivilegedDB();
         Statement st = con.createStatement()) {
      String owner = con.getMetaData().getUserName();
      try {
        st.execute("drop table if exists public.column_grant_only_test");
        st.execute("drop role if exists column_grant_only");
        st.execute("create user column_grant_only");
        st.execute("create table public.column_grant_only_test (c1 int, c2 int)");
        // Materialise relacl, then drop the owner's table-level INSERT so no one holds INSERT
        // at the table level.
        st.execute("grant select on column_grant_only_test to column_grant_only");
        st.execute("revoke insert on column_grant_only_test from " + owner);
        // INSERT now exists only at the column level, on c1.
        st.execute("grant insert (c1) on column_grant_only_test to column_grant_only");

        List<String> rows = new ArrayList<>();
        try (ResultSet rs =
                 con.getMetaData().getColumnPrivileges(null, "public", "column_grant_only_test", null)) {
          while (rs.next()) {
            String grantee = rs.getString("GRANTEE");
            if (owner.equals(grantee)) {
              continue;
            }
            rows.add(rs.getString("PRIVILEGE") + " " + grantee + " " + rs.getString("COLUMN_NAME"));
          }
        }

        assertEquals(1, Collections.frequency(rows, "INSERT column_grant_only c1"),
            "a column-only INSERT must be reported even when no one holds INSERT at the table level");
        assertEquals(0, Collections.frequency(rows, "INSERT column_grant_only c2"),
            "the column-only INSERT must not leak onto a column that was not granted it");
      } finally {
        st.execute("drop table if exists public.column_grant_only_test");
        st.execute("drop role if exists column_grant_only");
      }
    }
  }

  /**
   * A table-level grant and a column-level grant of the same privilege to the same role are
   * distinct records when they differ in the grant option, so both are reported. The table-level
   * {@code SELECT WITH GRANT OPTION} (IS_GRANTABLE {@code YES}) and the column-level {@code SELECT}
   * (IS_GRANTABLE {@code NO}) are kept as separate rows rather than de-duplicated, matching
   * {@code information_schema.column_privileges}.
   */
  @Test
  void keepsTableAndColumnGrantsThatDifferInGrantOption() throws Exception {
    try (Connection con = TestUtil.openPrivilegedDB();
         Statement st = con.createStatement()) {
      try {
        st.execute("drop table if exists public.column_grant_go_test");
        st.execute("drop role if exists column_grant_go");
        st.execute("create user column_grant_go");
        st.execute("create table public.column_grant_go_test (c1 int, c2 int)");
        // table-level SELECT is grantable; the column-level SELECT on c1 is not
        st.execute("grant select on column_grant_go_test to column_grant_go with grant option");
        st.execute("grant select (c1) on column_grant_go_test to column_grant_go");

        DatabaseMetaData md = con.getMetaData();
        String owner = md.getUserName();

        List<String> rows = new ArrayList<>();
        try (ResultSet rs =
                 md.getColumnPrivileges(null, "public", "column_grant_go_test", null)) {
          while (rs.next()) {
            String grantee = rs.getString("GRANTEE");
            if (owner.equals(grantee)) {
              continue;
            }
            rows.add(rs.getString("PRIVILEGE") + " " + grantee + " "
                + rs.getString("COLUMN_NAME") + " " + rs.getString("IS_GRANTABLE"));
          }
        }

        // c1 carries both the grantable table-level grant and the non-grantable column-level grant
        assertEquals(1, Collections.frequency(rows, "SELECT column_grant_go c1 YES"),
            "the grantable table-level SELECT must be reported on c1");
        assertEquals(1, Collections.frequency(rows, "SELECT column_grant_go c1 NO"),
            "the non-grantable column-level SELECT must be kept as a separate row on c1");
        // c2 has no column-level grant, so only the grantable table-level grant applies
        assertEquals(1, Collections.frequency(rows, "SELECT column_grant_go c2 YES"),
            "the grantable table-level SELECT must be reported on c2");
        assertEquals(0, Collections.frequency(rows, "SELECT column_grant_go c2 NO"),
            "no non-grantable SELECT should appear on c2");
      } finally {
        st.execute("drop table if exists public.column_grant_go_test");
        st.execute("drop role if exists column_grant_go");
      }
    }
  }

  /**
   * A column-level grant on a table that has no table-level grants leaves {@code relacl} NULL, so
   * the merge starts from the privileges synthesised for the owner rather than from a parsed
   * {@code relacl}. The other tests always issue a table-level grant first, which materialises
   * {@code relacl}; this one exercises the {@code relacl == null && attacl != null} path. The owner
   * holds every privilege on every column (here asserted for {@code SELECT}, which matches
   * {@code information_schema.column_privileges}), and the column grant is reported on its column.
   */
  @Test
  void reportsColumnGrantWhenNoTableLevelGrantsExist() throws Exception {
    try (Connection con = TestUtil.openPrivilegedDB();
         Statement st = con.createStatement()) {
      try {
        st.execute("drop table if exists public.column_grant_norel_test");
        st.execute("drop role if exists column_grant_norel");
        st.execute("create user column_grant_norel");
        st.execute("create table public.column_grant_norel_test (c1 int, c2 int)");
        // Only a column-level grant, so the table keeps a NULL relacl.
        st.execute("grant select (c1) on column_grant_norel_test to column_grant_norel");

        DatabaseMetaData md = con.getMetaData();
        String owner = md.getUserName();

        // Keep every row, including the owner's, to assert the synthesised owner privileges.
        List<String> rows = new ArrayList<>();
        try (ResultSet rs =
                 md.getColumnPrivileges(null, "public", "column_grant_norel_test", null)) {
          while (rs.next()) {
            rows.add(rs.getString("PRIVILEGE") + " " + rs.getString("GRANTEE") + " "
                + rs.getString("COLUMN_NAME"));
          }
        }

        // The column grantee appears only on the granted column.
        assertEquals(1, Collections.frequency(rows, "SELECT column_grant_norel c1"),
            "the column-level SELECT must be reported on its column even with a NULL relacl");
        assertEquals(0, Collections.frequency(rows, "SELECT column_grant_norel c2"),
            "the column-level SELECT must not appear on a column it was not granted on");
        // The owner's privileges are synthesised onto every column despite the NULL relacl.
        assertEquals(1, Collections.frequency(rows, "SELECT " + owner + " c1"),
            "the owner's SELECT must be synthesised on c1");
        assertEquals(1, Collections.frequency(rows, "SELECT " + owner + " c2"),
            "the owner's SELECT must be synthesised on c2");
      } finally {
        st.execute("drop table if exists public.column_grant_norel_test");
        st.execute("drop role if exists column_grant_norel");
      }
    }
  }
}
