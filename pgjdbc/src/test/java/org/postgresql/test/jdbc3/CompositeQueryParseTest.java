package org.postgresql.test.jdbc3;

import org.postgresql.core.NativeQuery;
import org.postgresql.core.Parser;
import org.postgresql.core.SqlCommandType;

import junit.framework.TestCase;

import java.sql.SQLException;
import java.util.List;

public class CompositeQueryParseTest extends TestCase {

  public void testEmptyQuery() {
    assertEquals("", reparse("", true, false, true));
  }

  public void testWhitespaceQuery() {
    assertEquals("", reparse("     ", true, false, true));
  }

  public void testOnlyEmptyQueries() {
    assertEquals("", reparse(";;;;  ;  \n;\n", true, false, true));
  }

  public void testSimpleQuery() {
    assertEquals("select 1", reparse("select 1", true, false, true));
  }

  public void testSimpleBind() {
    assertEquals("select $1", reparse("select ?", true, true, true));
  }

  public void testUnquotedQuestionmark() {
    assertEquals("select '{\"key\": \"val\"}'::jsonb ? 'key'",
        reparse("select '{\"key\": \"val\"}'::jsonb ? 'key'", true, false, true));
  }

  public void testRepeatedQuestionmark() {
    assertEquals("select '{\"key\": \"val\"}'::jsonb ? 'key'",
        reparse("select '{\"key\": \"val\"}'::jsonb ?? 'key'", true, false, true));
  }

  public void testQuotedQuestionmark() {
    assertEquals("select '?'", reparse("select '?'", true, false, true));
  }

  public void testDoubleQuestionmark() {
    assertEquals("select '?', $1 ?=> $2", reparse("select '?', ? ??=> ?", true, true, true));
  }

  public void testCompositeBasic() {
    assertEquals("select 1;/*cut*/\n select 2", reparse("select 1; select 2", true, false, true));
  }

  public void testCompositeWithBinds() {
    assertEquals("select $1;/*cut*/\n select $1", reparse("select ?; select ?", true, true, true));
  }

  public void testTrailingSemicolon() {
    assertEquals("select 1", reparse("select 1;", true, false, true));
  }

  public void testTrailingSemicolonAndSpace() {
    assertEquals("select 1", reparse("select 1; ", true, false, true));
  }

  public void testMultipleTrailingSemicolons() {
    assertEquals("select 1", reparse("select 1;;;", true, false, true));
  }

  public void testHasReturning() throws SQLException {

    List<NativeQuery> queries = Parser.parseJdbcSql("insert into foo (a,b,c) values (?,?,?) RetuRning a", true, true, false,
        true);
    NativeQuery query = queries.get(0);
    assertTrue("The parser should find the word returning", query.command.isReturningKeywordPresent());

    queries = Parser.parseJdbcSql("insert into foo (a,b,c) values (?,?,?)", true, true, false, true);
    query = queries.get(0);
    assertFalse("The parser should not find the word returning", query.command.isReturningKeywordPresent());

    queries = Parser.parseJdbcSql("insert into foo (a,b,c) values ('returning',?,?)", true, true, false,
        true);
    query = queries.get(0);
    assertFalse("The parser should not find the word returning as it is in quotes ", query.command.isReturningKeywordPresent());
  }

  public void testSelect() throws SQLException {
    List<NativeQuery> queries;
    NativeQuery query;
    queries = Parser.parseJdbcSql("select 1 as returning from (update table)", true, true, false, true);
    query = queries.get(0);
    assertEquals("This is a select ", SqlCommandType.SELECT, query.command.getType());
    assertTrue("Returning is OK here as it is not an insert command ", query.command.isReturningKeywordPresent());
  }

  public void testDelete() throws SQLException {
    List<NativeQuery> queries = Parser.parseJdbcSql("DeLeTe from foo where a=1", true, true, false,
        true);
    NativeQuery query = queries.get(0);
    assertEquals("This is a delete command", SqlCommandType.DELETE, query.command.getType());
  }


  public void testMove() throws SQLException {
    List<NativeQuery> queries = Parser.parseJdbcSql("MoVe NEXT FROM FOO", true, true, false, true);
    NativeQuery query = queries.get(0);
    assertEquals("This is a move command", SqlCommandType.MOVE, query.command.getType());
  }

  public void testUpdate() throws SQLException {
    List<NativeQuery> queries;
    NativeQuery query;
    queries = Parser.parseJdbcSql("update foo set (a=?,b=?,c=?)", true, true, false, true);
    query = queries.get(0);
    assertEquals("This is an UPDATE command", SqlCommandType.UPDATE, query.command.getType());
  }

  public void testInsert() throws SQLException {
    List<NativeQuery> queries = Parser.parseJdbcSql("InSeRt into foo (a,b,c) values (?,?,?) returning a", true, true, false,
        true);
    NativeQuery query = queries.get(0);
    assertEquals("This is an INSERT command", SqlCommandType.INSERT, query.command.getType());

    queries = Parser.parseJdbcSql("select 1 as insert", true, true, false, true);
    query = queries.get(0);
    assertEquals("This is a SELECT command", SqlCommandType.SELECT, query.command.getType());
  }

  public void testWith() throws SQLException {
    List<NativeQuery> queries;
    NativeQuery query;
    queries = Parser.parseJdbcSql("with update as insert (update foo set (a=?,b=?,c=?)) select * from update", true, true, false, true);
    query = queries.get(0);
    assertEquals("This is a WITH command", SqlCommandType.WITH, query.command.getType());
  }

  public void testMultipleEmptyQueries() {
    assertEquals("select 1;/*cut*/\n" + "select 2",
        reparse("select 1; ;\t;select 2", true, false, true));
  }

  public void testCompositeWithComments() {
    assertEquals("select 1;/*cut*/\n" + "/* noop */;/*cut*/\n" + "select 2",
        reparse("select 1;/* noop */;select 2", true, false, true));
  }

  private String reparse(String query, boolean standardConformingStrings, boolean withParameters,
      boolean splitStatements) {
    try {
      return toString(
          Parser.parseJdbcSql(query, standardConformingStrings, withParameters, splitStatements, false));
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
