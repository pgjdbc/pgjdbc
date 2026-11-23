/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ReturningParserTest {
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();

    String[] delimiters = {"", "_", "3", "*", " "};

    for (String columnName : new String[]{"returning", "returningreturning"}) {
      for (String prefix : delimiters) {
        for (String suffix : delimiters) {
          for (String returning : new String[]{"returning", "returningreturning"}) {
            ids.add(new Object[]{columnName, returning, prefix, suffix});
          }
        }
      }
    }
    return ids;
  }

  @MethodSource("data")
  @ParameterizedTest(name = "columnName={2} {0} {3}, returning={2} {1} {3}")
  void test(String columnName, String returning, String prefix, String suffix) throws SQLException {
    String query =
        "insert into\"prep\"(a, " + prefix + columnName + suffix + ")values(1,2)" + prefix
            + returning + suffix;
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true);
    boolean returningKeywordPresent = qry.get(0).command.isReturningKeywordPresent();

    boolean expectedReturning = "returning".equalsIgnoreCase(returning)
        && (prefix.isEmpty() || !Character.isJavaIdentifierStart(prefix.charAt(0)))
        && (suffix.isEmpty() || !Character.isJavaIdentifierPart(suffix.charAt(0)));
    if (expectedReturning != returningKeywordPresent) {
      assertEquals(expectedReturning,
          returningKeywordPresent,
          "Wrong <returning_clause> detected in SQL " + query);
    }
  }

  /**
   * Every row requests generated keys as {@code *}, so a statement typed INSERT, UPDATE, DELETE,
   * MERGE, or WITH expects {@code \nRETURNING *} at the end of its native SQL.
   */
  @ParameterizedTest
  @MethodSource("firstWordStatements")
  void commandTypeComesFromTheFirstWordOfTheStatement(String sql, SqlCommandType type,
      String nativeSql) throws SQLException {
    List<NativeQuery> queries = Parser.parseJdbcSql(sql, true, true, true, true, true, "*");
    assertAll(
        () -> assertEquals(1, queries.size(), () -> "statement count of " + sql),
        () -> assertEquals(type, queries.get(0).command.getType(), () -> "command type of " + sql),
        () -> assertEquals(nativeSql, queries.get(0).nativeSql));
  }

  static Stream<Arguments> firstWordStatements() {
    return Stream.of(
        argumentSet("insert", "insert into t values (1)",
            SqlCommandType.INSERT, "insert into t values (1)\nRETURNING *"),
        argumentSet("update", "update t set a = 1",
            SqlCommandType.UPDATE, "update t set a = 1\nRETURNING *"),
        argumentSet("delete", "delete from t",
            SqlCommandType.DELETE, "delete from t\nRETURNING *"),
        argumentSet("merge", "merge into t using s on t.id = s.id when matched then update set a = 1",
            SqlCommandType.MERGE,
            "merge into t using s on t.id = s.id when matched then update set a = 1\nRETURNING *"),
        argumentSet("merge that only inserts",
            "merge into t using s on t.id = s.id when not matched then insert values (s.id)",
            SqlCommandType.MERGE,
            "merge into t using s on t.id = s.id when not matched then insert values (s.id)"
                + "\nRETURNING *"),
        argumentSet("merge from a subquery",
            "merge into t using (select 1 as id) s on t.id = s.id when matched then update set a = 1",
            SqlCommandType.MERGE,
            "merge into t using (select 1 as id) s on t.id = s.id when matched then update set a = 1"
                + "\nRETURNING *"),
        argumentSet("merge that does nothing",
            "merge into t using s on t.id = s.id when matched then do nothing",
            SqlCommandType.MERGE,
            "merge into t using s on t.id = s.id when matched then do nothing\nRETURNING *"),
        argumentSet("with, then insert", "with x as (select 1) insert into t select * from x",
            SqlCommandType.INSERT, "with x as (select 1) insert into t select * from x\nRETURNING *"),
        argumentSet("a comment before the first word", "/* c */ delete from t",
            SqlCommandType.DELETE, "/* c */ delete from t\nRETURNING *"),
        argumentSet("a parenthesis before the first word", "(select 1) union (select 2)",
            SqlCommandType.SELECT, "(select 1) union (select 2)"),
        argumentSet("insert as a name, then a space", "drop table insert ",
            SqlCommandType.BLANK, "drop table insert "),
        argumentSet("insert as a name, then a comment", "drop table insert/*c*/",
            SqlCommandType.BLANK, "drop table insert/*c*/"),
        argumentSet("insert as a name, then a semicolon", "listen insert;",
            SqlCommandType.BLANK, "listen insert"),
        argumentSet("insert as a name at the end of the input", "drop table insert",
            SqlCommandType.BLANK, "drop table insert"),
        argumentSet("insert inside copy", "copy (insert into t values (1) returning a) to stdout",
            SqlCommandType.BLANK, "copy (insert into t values (1) returning a) to stdout"),
        argumentSet("with clause of copy", "copy t to stdout with (format csv)",
            SqlCommandType.BLANK, "copy t to stdout with (format csv)"),
        argumentSet("analyze, then insert as a name", "analyze insert (a)",
            SqlCommandType.BLANK, "analyze insert (a)"),
        argumentSet("prepare, then insert", "prepare p as insert into t values (1)",
            SqlCommandType.INSERT, "prepare p as insert into t values (1)\nRETURNING *"),
        argumentSet("prepare in upper case", "PREPARE P AS INSERT INTO t VALUES (1)",
            SqlCommandType.INSERT, "PREPARE P AS INSERT INTO t VALUES (1)\nRETURNING *"),
        argumentSet("prepare, then merge",
            "prepare p as merge into t using s on t.id = s.id when matched then delete",
            SqlCommandType.MERGE,
            "prepare p as merge into t using s on t.id = s.id when matched then delete\nRETURNING *"),
        argumentSet("prepare named insert", "prepare insert as delete from t",
            SqlCommandType.DELETE, "prepare insert as delete from t\nRETURNING *"),
        argumentSet("prepare with parameter types", "prepare p (int, text) as update t set a = $1",
            SqlCommandType.UPDATE, "prepare p (int, text) as update t set a = $1\nRETURNING *"),
        argumentSet("explain", "explain insert into t values (1)",
            SqlCommandType.BLANK, "explain insert into t values (1)"),
        argumentSet("explain with bare options", "explain analyze verbose delete from t",
            SqlCommandType.BLANK, "explain analyze verbose delete from t"),
        argumentSet("explain with an option list", "explain (format json) insert into t values (1)",
            SqlCommandType.BLANK, "explain (format json) insert into t values (1)"),
        argumentSet("explain, then with", "explain with x as (select 1) insert into t select * from x",
            SqlCommandType.BLANK, "explain with x as (select 1) insert into t select * from x"),
        argumentSet("explain, then merge",
            "explain merge into t using s on t.id = s.id when matched then update set a = 1",
            SqlCommandType.BLANK,
            "explain merge into t using s on t.id = s.id when matched then update set a = 1"),
        argumentSet("create function with begin atomic",
            "create function f() returns int language sql begin atomic select 1; end",
            SqlCommandType.CREATE,
            "create function f() returns int language sql begin atomic select 1; end"));
  }

  /**
   * A word that ends at a semicolon belongs to the statement it ends: it neither types the next
   * statement nor counts as that statement's first word.
   */
  @ParameterizedTest
  @MethodSource("multiStatementQueries")
  void eachStatementTakesItsCommandTypeFromItsOwnFirstWord(String sql, List<String> statements)
      throws SQLException {
    List<NativeQuery> queries = Parser.parseJdbcSql(sql, true, true, true, true, true, "*");
    assertEquals(statements,
        queries.stream()
            .map(q -> q.command.getType() + ": " + q.nativeSql)
            .collect(Collectors.toList()),
        () -> "statements of " + sql);
  }

  static Stream<Arguments> multiStatementQueries() {
    return Stream.of(
        argumentSet("a space before each semicolon", "listen x ; delete from t ; listen update",
            Arrays.asList("BLANK: listen x ", "DELETE:  delete from t \nRETURNING *",
                "BLANK:  listen update")),
        argumentSet("a name before the semicolon", "listen x; delete from t; listen update",
            Arrays.asList("BLANK: listen x", "DELETE:  delete from t\nRETURNING *",
                "BLANK:  listen update")),
        argumentSet("a keyword before the semicolon, then delete", "listen update; delete from t",
            Arrays.asList("BLANK: listen update", "DELETE:  delete from t\nRETURNING *")),
        argumentSet("a keyword before the semicolon, then select", "notify update; select 1",
            Arrays.asList("BLANK: notify update", "SELECT:  select 1")));
  }

  @Test
  void explainWithItsOwnReturningIsMarkedAsReturningRows() throws SQLException {
    String sql = "explain insert into t values (1) returning a";
    NativeQuery query = Parser.parseJdbcSql(sql, true, true, true, true, true, "*").get(0);
    assertAll(
        () -> assertEquals(SqlCommandType.BLANK, query.command.getType(), "command type"),
        () -> assertEquals(sql, query.nativeSql),
        () -> assertTrue(query.command.isReturningKeywordPresent(),
            "isReturningKeywordPresent(); pgjdbc 42.7.14 returns true as well, and PgStatement then"
                + " returns the plan as generated keys"));
  }
}
