/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code getColumnPrivileges} reports, for each column, the grants made on the table together with
 * the grants made on that column, as {@code information_schema.column_privileges} does. A grant
 * held at both levels with the same grantor and grant option is reported once; grants that differ
 * in either are reported separately.
 *
 * <p>The table-level grants used to be replaced, privilege by privilege, by the column-level ones:
 * a privilege that appeared in a column's ACL lost its table-level grantees, and a column with no
 * ACL of its own reported only the owner's default privileges.</p>
 *
 * <p>Roles are shared by every database on the server, so the role and table names carry a suffix
 * unique to this run. A row is written as {@code "column privilege grantor->grantee is_grantable"},
 * with the roles shown as {@code one} and {@code two} and the table owner as {@code owner}.</p>
 */
class ColumnPrivilegesTest {
  private static final String SUFFIX =
      "_" + Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 1);
  private static final String ONE = "colpriv_one" + SUFFIX;
  private static final String TWO = "colpriv_two" + SUFFIX;
  private static final String TABLE = "colpriv_test" + SUFFIX;

  private static Connection con;

  @BeforeAll
  static void createRoles() throws SQLException {
    con = TestUtil.openPrivilegedDB();
    TestUtil.execute(con, "create role " + ONE);
    TestUtil.execute(con, "create role " + TWO);
  }

  @AfterAll
  static void dropRoles() throws SQLException {
    if (con == null) {
      return;
    }
    try {
      TestUtil.execute(con, "drop role if exists " + ONE);
      TestUtil.execute(con, "drop role if exists " + TWO);
    } finally {
      con.close();
    }
  }

  @BeforeEach
  void createTable() throws SQLException {
    TestUtil.execute(con, "create table " + TABLE + " (c1 int, c2 int)");
  }

  @AfterEach
  void dropTable() throws SQLException {
    TestUtil.execute(con, "drop table if exists " + TABLE);
  }

  @Test
  void aTableGrantIsReportedOnEveryColumn() throws SQLException {
    execute("grant select on " + TABLE + " to " + ONE);

    assertEquals(
        Arrays.asList(
            "c1 SELECT owner->one NO",
            "c1 SELECT owner->owner YES",
            "c2 SELECT owner->one NO",
            "c2 SELECT owner->owner YES"),
        columnPrivileges("SELECT"));
  }

  /**
   * Without a column grant, a column reports exactly the table's privileges, so the owner's
   * table-level {@code INSERT}, once revoked, is reported on no column.
   */
  @Test
  void aPrivilegeRevokedFromTheOwnerIsReportedOnNoColumn() throws SQLException {
    execute("revoke insert on " + TABLE + " from current_user");

    assertEquals(
        Collections.emptyList(),
        columnPrivileges("INSERT"));
  }

  @Test
  void aColumnGrantOfTheSamePrivilegeToAnotherRoleKeepsTheTableGrant() throws SQLException {
    execute("grant select on " + TABLE + " to " + ONE);
    execute("grant select (c1) on " + TABLE + " to " + TWO);

    assertEquals(
        Arrays.asList(
            "c1 SELECT owner->one NO",
            "c1 SELECT owner->owner YES",
            "c1 SELECT owner->two NO",
            "c2 SELECT owner->one NO",
            "c2 SELECT owner->owner YES"),
        columnPrivileges("SELECT"));
  }

  @Test
  void aColumnGrantOfAnotherPrivilegeKeepsTheTableGrant() throws SQLException {
    execute("grant select on " + TABLE + " to " + ONE);
    execute("grant update (c1) on " + TABLE + " to " + TWO);

    assertEquals(
        Arrays.asList(
            "c1 SELECT owner->one NO",
            "c1 SELECT owner->owner YES",
            "c2 SELECT owner->one NO",
            "c2 SELECT owner->owner YES"),
        columnPrivileges("SELECT"));
  }

  @Test
  void aColumnGrantIsReportedOnlyOnItsColumn() throws SQLException {
    execute("grant update (c1) on " + TABLE + " to " + TWO);

    assertEquals(
        Arrays.asList(
            "c1 UPDATE owner->owner YES",
            "c1 UPDATE owner->two NO",
            "c2 UPDATE owner->owner YES"),
        columnPrivileges("UPDATE"));
  }

  @Test
  void aGrantHeldAtBothLevelsIsReportedOnce() throws SQLException {
    execute("grant select on " + TABLE + " to " + ONE);
    execute("grant select (c1) on " + TABLE + " to " + ONE);

    assertEquals(
        Arrays.asList(
            "c1 SELECT owner->one NO",
            "c1 SELECT owner->owner YES",
            "c2 SELECT owner->one NO",
            "c2 SELECT owner->owner YES"),
        columnPrivileges("SELECT"));
  }

  /**
   * {@code PUBLIC} appears in an ACL as an empty role name.
   */
  @Test
  void aPublicGrantHeldAtBothLevelsIsReportedOnce() throws SQLException {
    execute("grant select on " + TABLE + " to public");
    execute("grant select (c1) on " + TABLE + " to public");

    assertEquals(
        Arrays.asList(
            "c1 SELECT owner->PUBLIC NO",
            "c1 SELECT owner->owner YES",
            "c2 SELECT owner->PUBLIC NO",
            "c2 SELECT owner->owner YES"),
        columnPrivileges("SELECT"));
  }

  @Test
  void grantsThatDifferInTheGrantOptionAreReportedSeparately() throws SQLException {
    execute("grant select on " + TABLE + " to " + ONE + " with grant option");
    execute("grant select (c1) on " + TABLE + " to " + ONE);

    assertEquals(
        Arrays.asList(
            "c1 SELECT owner->one NO",
            "c1 SELECT owner->one YES",
            "c1 SELECT owner->owner YES",
            "c2 SELECT owner->one YES",
            "c2 SELECT owner->owner YES"),
        columnPrivileges("SELECT"));
  }

  /**
   * {@code one} grants the column privilege through the grant option it holds on the table, so the
   * column grant to {@code two} has another grantor than the table grant to {@code two}.
   */
  @Test
  void grantsThatDifferInTheGrantorAreReportedSeparately() throws SQLException {
    execute("grant select on " + TABLE + " to " + ONE + " with grant option");
    execute("grant select on " + TABLE + " to " + TWO);
    execute("grant usage on schema public to " + ONE);
    try {
      execute("set role " + ONE);
      try {
        execute("grant select (c1) on " + TABLE + " to " + TWO);
      } finally {
        execute("reset role");
      }
    } finally {
      execute("revoke usage on schema public from " + ONE);
    }

    assertEquals(
        Arrays.asList(
            "c1 SELECT one->two NO",
            "c1 SELECT owner->one YES",
            "c1 SELECT owner->owner YES",
            "c1 SELECT owner->two NO",
            "c2 SELECT owner->one YES",
            "c2 SELECT owner->owner YES",
            "c2 SELECT owner->two NO"),
        columnPrivileges("SELECT"));
  }

  /**
   * Revoking the owner's table-level {@code INSERT} leaves no table-level holder of
   * {@code INSERT}, so the column grant is the only {@code INSERT} on the table.
   */
  @Test
  void aColumnGrantOfAPrivilegeNobodyHoldsOnTheTableIsReportedOnItsColumnOnly()
      throws SQLException {
    execute("revoke insert on " + TABLE + " from current_user");
    execute("grant insert (c1) on " + TABLE + " to " + TWO);

    assertEquals(
        Collections.singletonList("c1 INSERT owner->two NO"),
        columnPrivileges("INSERT"));
  }

  /**
   * With no table-level grant, {@code relacl} stays NULL and the owner holds its default
   * privileges; the column grant on {@code c1} adds to them.
   */
  @Test
  void theOwnerKeepsItsDefaultPrivilegesOnAColumnWithAColumnGrant() throws SQLException {
    execute("grant select (c1) on " + TABLE + " to " + TWO);

    assertEquals(
        Arrays.asList(
            "c1 SELECT owner->owner YES",
            "c1 SELECT owner->two NO",
            "c2 SELECT owner->owner YES"),
        columnPrivileges("SELECT"));
  }

  private static void execute(String sql) throws SQLException {
    TestUtil.execute(con, sql);
  }

  /**
   * Returns the rows {@code getColumnPrivileges} reports for {@link #TABLE} with the given
   * privilege, in the form the class comment describes, sorted.
   */
  private static List<String> columnPrivileges(String privilege) throws SQLException {
    String owner = con.getMetaData().getUserName();
    List<String> rows = new ArrayList<>();
    try (ResultSet rs = con.getMetaData().getColumnPrivileges(null, "public", TABLE, null)) {
      while (rs.next()) {
        if (!privilege.equals(rs.getString("PRIVILEGE"))) {
          continue;
        }
        rows.add(rs.getString("COLUMN_NAME") + " " + rs.getString("PRIVILEGE") + " "
            + roleName(rs.getString("GRANTOR"), owner) + "->"
            + roleName(rs.getString("GRANTEE"), owner) + " "
            + rs.getString("IS_GRANTABLE"));
      }
    }
    Collections.sort(rows);
    return rows;
  }

  private static String roleName(String role, String owner) {
    if (role.equals(owner)) {
      return "owner";
    }
    if (role.equals(ONE)) {
      return "one";
    }
    if (role.equals(TWO)) {
      return "two";
    }
    return role;
  }
}
