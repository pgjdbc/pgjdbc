/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.NativeQuery;
import org.postgresql.core.Parser;
import org.postgresql.core.SqlCommandType;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

class CompositeQueryParseTest {

  @Test
  void emptyQuery() {
    assertEquals("", reparse("", true, false, true));
  }

  @Test
  void whitespaceQuery() {
    assertEquals("", reparse("     ", true, false, true));
  }

  @Test
  void onlyEmptyQueries() {
    assertEquals("", reparse(";;;;  ;  \n;\n", true, false, true));
  }

  @Test
  void simpleQuery() {
    assertEquals("select 1", reparse("select 1", true, false, true));
  }

  @Test
  void simpleBind() {
    assertEquals("select $1", reparse("select ?", true, true, true));
  }

  @Test
  void unquotedQuestionmark() {
    assertEquals("select '{\"key\": \"val\"}'::jsonb ? 'key'",
        reparse("select '{\"key\": \"val\"}'::jsonb ? 'key'", true, false, true));
  }

  @Test
  void repeatedQuestionmark() {
    assertEquals("select '{\"key\": \"val\"}'::jsonb ? 'key'",
        reparse("select '{\"key\": \"val\"}'::jsonb ?? 'key'", true, false, true));
  }

  @Test
  void quotedQuestionmark() {
    assertEquals("select '?'", reparse("select '?'", true, false, true));
  }

  @Test
  void doubleQuestionmark() {
    assertEquals("select '?', $1 ?=> $2", reparse("select '?', ? ??=> ?", true, true, true));
  }

  @Test
  void compositeBasic() {
    assertEquals("select 1;/*cut*/\n select 2", reparse("select 1; select 2", true, false, true));
  }

  @Test
  void compositeWithBinds() {
    assertEquals("select $1;/*cut*/\n select $1", reparse("select ?; select ?", true, true, true));
  }

  @Test
  void trailingSemicolon() {
    assertEquals("select 1", reparse("select 1;", true, false, true));
  }

  @Test
  void trailingSemicolonAndSpace() {
    assertEquals("select 1", reparse("select 1; ", true, false, true));
  }

  @Test
  void multipleTrailingSemicolons() {
    assertEquals("select 1", reparse("select 1;;;", true, false, true));
  }

  @Test
  void hasReturning() throws SQLException {
    List<NativeQuery> queries = Parser.parseJdbcSql("insert into foo (a,b,c) values (?,?,?) RetuRning a", true, true, false,
        true, true);
    NativeQuery query = queries.get(0);
    assertTrue(query.command.isReturningKeywordPresent(), "The parser should find the word returning");

    queries = Parser.parseJdbcSql("insert into foo (a,b,c) values (?,?,?)", true, true, false, true, true);
    query = queries.get(0);
    assertFalse(query.command.isReturningKeywordPresent(), "The parser should not find the word returning");

    queries = Parser.parseJdbcSql("insert into foo (a,b,c) values ('returning',?,?)", true, true, false,
        true, true);
    query = queries.get(0);
    assertFalse(query.command.isReturningKeywordPresent(), "The parser should not find the word returning as it is in quotes ");
  }

  @Test
  void select() throws SQLException {
    List<NativeQuery> queries;
    queries = Parser.parseJdbcSql("select 1 as returning from (update table)", true, true, false, true, true);
    NativeQuery query = queries.get(0);
    assertEquals(SqlCommandType.SELECT, query.command.getType(), "This is a select ");
    assertTrue(query.command.isReturningKeywordPresent(), "Returning is OK here as it is not an insert command ");
  }

  @Test
  void delete() throws SQLException {
    List<NativeQuery> queries = Parser.parseJdbcSql("DeLeTe from foo where a=1", true, true, false,
        true, true);
    NativeQuery query = queries.get(0);
    assertEquals(SqlCommandType.DELETE, query.command.getType(), "This is a delete command");
  }

  @Test
  void multiQueryWithBind() throws SQLException {
    // braces around (42) are required to puzzle the parser
    String sql = "INSERT INTO inttable(a) VALUES (?);SELECT (42)";
    List<NativeQuery> queries = Parser.parseJdbcSql(sql, true, true, true, true, true);
    NativeQuery query = queries.get(0);
    assertEquals("INSERT: INSERT INTO inttable(a) VALUES ($1)",
        query.command.getType() + ": " + query.nativeSql,
        "query(0) of " + sql);
    query = queries.get(1);
    assertEquals("SELECT: SELECT (42)",
        query.command.getType() + ": " + query.nativeSql,
        "query(1) of " + sql);
  }

  @Test
  void move() throws SQLException {
    List<NativeQuery> queries = Parser.parseJdbcSql("MoVe NEXT FROM FOO", true, true, false, true, true);
    NativeQuery query = queries.get(0);
    assertEquals(SqlCommandType.MOVE, query.command.getType(), "This is a move command");
  }

  @Test
  void update() throws SQLException {
    List<NativeQuery> queries;
    NativeQuery query;
    queries = Parser.parseJdbcSql("update foo set (a=?,b=?,c=?)", true, true, false, true, true);
    query = queries.get(0);
    assertEquals(SqlCommandType.UPDATE, query.command.getType(), "This is an UPDATE command");
  }

  @Test
  void insert() throws SQLException {
    List<NativeQuery> queries = Parser.parseJdbcSql("InSeRt into foo (a,b,c) values (?,?,?) returning a", true, true, false,
        true, true);
    NativeQuery query = queries.get(0);
    assertEquals(SqlCommandType.INSERT, query.command.getType(), "This is an INSERT command");

    queries = Parser.parseJdbcSql("select 1 as insert", true, true, false, true, true);
    query = queries.get(0);
    assertEquals(SqlCommandType.SELECT, query.command.getType(), "This is a SELECT command");
  }

  @Test
  void withSelect() throws SQLException {
    List<NativeQuery> queries;
    queries = Parser.parseJdbcSql("with update as (update foo set (a=?,b=?,c=?)) select * from update", true, true, false, true, true);
    NativeQuery query = queries.get(0);
    assertEquals(SqlCommandType.SELECT, query.command.getType(), "with ... () select");
  }

  @Test
  void withInsert() throws SQLException {
    List<NativeQuery> queries;
    queries = Parser.parseJdbcSql("with update as (update foo set (a=?,b=?,c=?)) insert into table(select) values(1)", true, true, false, true, true);
    NativeQuery query = queries.get(0);
    assertEquals(SqlCommandType.INSERT, query.command.getType(), "with ... () insert");
  }

  @Test
  void multipleEmptyQueries() {
    assertEquals("select 1;/*cut*/\n" + "select 2",
        reparse("select 1; ;\t;select 2", true, false, true));
  }

  @Test
  void compositeWithComments() {
    assertEquals("select 1;/*cut*/\n" + "/* noop */;/*cut*/\n" + "select 2",
        reparse("select 1;/* noop */;select 2", true, false, true));
  }

  private String reparse(String query, boolean standardConformingStrings, boolean withParameters,
      boolean splitStatements) {
    try {
      return toString(
          Parser.parseJdbcSql(query, standardConformingStrings, withParameters, splitStatements, false, true));
    } catch (SQLException e) {
      throw new IllegalStateException("Parser.parseJdbcSql: " + e.getMessage(), e);
    }
  }

  private String toString(List<NativeQuery> queries) {
    StringBuilder sb = new StringBuilder();
    for (NativeQuery query : queries) {
      if (sb.length() != 0) {
        sb.append(";/*cut*/\n");
      }
      sb.append(query.nativeSql);
    }
    return sb.toString();
  }
}
