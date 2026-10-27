/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A {@code Statement} that asks for generated keys runs every statement of a string that holds
 * several, and gets keys for a string of one statement followed by a comment, in the query modes
 * that send such a string to the server as one query.
 */
@ParameterizedClass
@MethodSource("data")
public class GeneratedKeysMultiStatementTest extends BaseTest4 {

  public GeneratedKeysMultiStatementTest(PreferQueryMode preferQueryMode) {
    setPreferQueryMode(preferQueryMode);
  }

  public static Iterable<PreferQueryMode> data() {
    return Arrays.asList(PreferQueryMode.SIMPLE, PreferQueryMode.EXTENDED_FOR_PREPARED);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTempTable(con, "genkeys_multi", "a serial, b int");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "genkeys_multi");
    super.tearDown();
  }

  /**
   * The update count is the first statement's, and no generated keys are offered, since the
   * first result the server sends belongs to the first statement. The separator between the
   * statements used to be dropped, and the server refused the glued text with 42601.
   */
  @Test
  public void severalStatementsAllRunAndReportTheFirstUpdateCount() throws SQLException {
    int count;
    List<Integer> keys;
    try (Statement stmt = con.createStatement()) {
      count = stmt.executeUpdate(
          "insert into genkeys_multi(b) values (1); insert into genkeys_multi(b) values (2), (3)",
          new String[]{"a"});
      keys = generatedKeys(stmt);
    }
    List<Integer> rows = valuesOfB();
    assertAll(
        () -> assertEquals(1, count, "update count"),
        () -> assertEquals(Collections.emptyList(), keys, "generated keys"),
        () -> assertEquals(Arrays.asList(1, 2, 3), rows, "values of b after the update")
    );
  }

  @Test
  public void severalStatementsRunThroughExecuteWithColumnNames() throws SQLException {
    boolean firstIsResultSet;
    int count;
    List<Integer> keys;
    try (Statement stmt = con.createStatement()) {
      firstIsResultSet = stmt.execute(
          "insert into genkeys_multi(b) values (1); insert into genkeys_multi(b) values (2), (3)",
          new String[]{"a"});
      count = stmt.getUpdateCount();
      keys = generatedKeys(stmt);
    }
    List<Integer> rows = valuesOfB();
    assertAll(
        () -> assertEquals(false, firstIsResultSet, "execute result"),
        () -> assertEquals(1, count, "update count"),
        () -> assertEquals(Collections.emptyList(), keys, "generated keys"),
        () -> assertEquals(Arrays.asList(1, 2, 3), rows, "values of b after the update")
    );
  }

  @Test
  public void severalStatementsRunWithReturnGeneratedKeys() throws SQLException {
    int count;
    List<Integer> keys;
    try (Statement stmt = con.createStatement()) {
      count = stmt.executeUpdate(
          "insert into genkeys_multi(b) values (1); insert into genkeys_multi(b) values (2), (3)",
          Statement.RETURN_GENERATED_KEYS);
      keys = generatedKeys(stmt);
    }
    List<Integer> rows = valuesOfB();
    assertAll(
        () -> assertEquals(1, count, "update count"),
        () -> assertEquals(Collections.emptyList(), keys, "generated keys"),
        () -> assertEquals(Arrays.asList(1, 2, 3), rows, "values of b after the update")
    );
  }

  /**
   * The first result the server sends is the rows of the first statement, so there is no update
   * count to return.
   */
  @Test
  public void firstStatementReturningRowsLeavesNoUpdateCount() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      int count = stmt.executeUpdate(
          "insert into genkeys_multi(b) values (1) returning b; insert into genkeys_multi(b)"
              + " values (2)",
          new String[]{"a"});
      List<Integer> keys = generatedKeys(stmt);
      assertAll(
          () -> assertEquals(-1, count, "update count"),
          () -> assertEquals(Collections.emptyList(), keys, "generated keys")
      );
    }
  }

  /**
   * A RETURNING clause the last statement carries does not turn its rows into the generated keys,
   * since the first result the server sends is still the first statement's update count.
   */
  @Test
  public void returningInTheLastStatementLeavesTheFirstUpdateCount() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      int count = stmt.executeUpdate(
          "insert into genkeys_multi(b) values (1); insert into genkeys_multi(b) values (2), (3)"
              + " returning a",
          new String[]{"a"});
      List<Integer> keys = generatedKeys(stmt);
      assertAll(
          () -> assertEquals(1, count, "update count"),
          () -> assertEquals(Collections.emptyList(), keys, "generated keys")
      );
    }
  }

  /**
   * The RETURNING clause goes in front of the separator, and the server accepts the text with the
   * comment after it.
   */
  @Test
  public void oneStatementFollowedByACommentReturnsGeneratedKeys() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      int count = stmt.executeUpdate("insert into genkeys_multi(b) values (5); -- audit",
          new String[]{"a"});
      List<Integer> keys = generatedKeys(stmt);
      assertAll(
          () -> assertEquals(1, count, "update count"),
          () -> assertEquals(Arrays.asList(1), keys, "generated keys")
      );
    }
  }

  private static List<Integer> generatedKeys(Statement stmt) throws SQLException {
    List<Integer> keys = new ArrayList<>();
    try (ResultSet rs = stmt.getGeneratedKeys()) {
      while (rs.next()) {
        keys.add(rs.getInt("a"));
      }
    }
    return keys;
  }

  private List<Integer> valuesOfB() throws SQLException {
    List<Integer> values = new ArrayList<>();
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("select b from genkeys_multi order by b")) {
      while (rs.next()) {
        values.add(rs.getInt(1));
      }
    }
    return values;
  }
}
