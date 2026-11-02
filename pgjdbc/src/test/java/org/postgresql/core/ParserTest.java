/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.postgresql.jdbc.EscapeSyntaxCallMode;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Test cases for the Parser.
 * @author Jeremy Whiting jwhiting@redhat.com
 */
class ParserTest {

  /**
   * Test to make sure delete command is detected by parser and detected via
   * api. Mix up the case of the command to check detection continues to work.
   */
  @Test
  void deleteCommandParsing() {
    char[] command = new char[6];
    "DELETE".getChars(0, 6, command, 0);
    assertTrue(Parser.parseDeleteKeyword(command, 0), "Failed to correctly parse upper case command.");
    "DelEtE".getChars(0, 6, command, 0);
    assertTrue(Parser.parseDeleteKeyword(command, 0), "Failed to correctly parse mixed case command.");
    "deleteE".getChars(0, 6, command, 0);
    assertTrue(Parser.parseDeleteKeyword(command, 0), "Failed to correctly parse mixed case command.");
    "delete".getChars(0, 6, command, 0);
    assertTrue(Parser.parseDeleteKeyword(command, 0), "Failed to correctly parse lower case command.");
    "Delete".getChars(0, 6, command, 0);
    assertTrue(Parser.parseDeleteKeyword(command, 0), "Failed to correctly parse mixed case command.");
  }

  /**
   * Test UPDATE command parsing.
   */
  @Test
  void updateCommandParsing() {
    char[] command = new char[6];
    "UPDATE".getChars(0, 6, command, 0);
    assertTrue(Parser.parseUpdateKeyword(command, 0), "Failed to correctly parse upper case command.");
    "UpDateE".getChars(0, 6, command, 0);
    assertTrue(Parser.parseUpdateKeyword(command, 0), "Failed to correctly parse mixed case command.");
    "updatE".getChars(0, 6, command, 0);
    assertTrue(Parser.parseUpdateKeyword(command, 0), "Failed to correctly parse mixed case command.");
    "Update".getChars(0, 6, command, 0);
    assertTrue(Parser.parseUpdateKeyword(command, 0), "Failed to correctly parse mixed case command.");
    "update".getChars(0, 6, command, 0);
    assertTrue(Parser.parseUpdateKeyword(command, 0), "Failed to correctly parse lower case command.");
  }

  /**
   * Test MOVE command parsing.
   */
  @Test
  void moveCommandParsing() {
    char[] command = new char[4];
    "MOVE".getChars(0, 4, command, 0);
    assertTrue(Parser.parseMoveKeyword(command, 0), "Failed to correctly parse upper case command.");
    "mOVe".getChars(0, 4, command, 0);
    assertTrue(Parser.parseMoveKeyword(command, 0), "Failed to correctly parse mixed case command.");
    "movE".getChars(0, 4, command, 0);
    assertTrue(Parser.parseMoveKeyword(command, 0), "Failed to correctly parse mixed case command.");
    "Move".getChars(0, 4, command, 0);
    assertTrue(Parser.parseMoveKeyword(command, 0), "Failed to correctly parse mixed case command.");
    "move".getChars(0, 4, command, 0);
    assertTrue(Parser.parseMoveKeyword(command, 0), "Failed to correctly parse lower case command.");
  }

  /**
   * Test WITH command parsing.
   */
  @Test
  void withCommandParsing() {
    char[] command = new char[4];
    "WITH".getChars(0, 4, command, 0);
    assertTrue(Parser.parseWithKeyword(command, 0), "Failed to correctly parse upper case command.");
    "wITh".getChars(0, 4, command, 0);
    assertTrue(Parser.parseWithKeyword(command, 0), "Failed to correctly parse mixed case command.");
    "witH".getChars(0, 4, command, 0);
    assertTrue(Parser.parseWithKeyword(command, 0), "Failed to correctly parse mixed case command.");
    "With".getChars(0, 4, command, 0);
    assertTrue(Parser.parseWithKeyword(command, 0), "Failed to correctly parse mixed case command.");
    "with".getChars(0, 4, command, 0);
    assertTrue(Parser.parseWithKeyword(command, 0), "Failed to correctly parse lower case command.");
  }

  /**
   * Test SELECT command parsing.
   */
  @Test
  void selectCommandParsing() {
    char[] command = new char[6];
    "SELECT".getChars(0, 6, command, 0);
    assertTrue(Parser.parseSelectKeyword(command, 0), "Failed to correctly parse upper case command.");
    "sELect".getChars(0, 6, command, 0);
    assertTrue(Parser.parseSelectKeyword(command, 0), "Failed to correctly parse mixed case command.");
    "selecT".getChars(0, 6, command, 0);
    assertTrue(Parser.parseSelectKeyword(command, 0), "Failed to correctly parse mixed case command.");
    "Select".getChars(0, 6, command, 0);
    assertTrue(Parser.parseSelectKeyword(command, 0), "Failed to correctly parse mixed case command.");
    "select".getChars(0, 6, command, 0);
    assertTrue(Parser.parseSelectKeyword(command, 0), "Failed to correctly parse lower case command.");
  }

  @Test
  void escapeProcessing() throws Exception {
    assertEquals("DATE '1999-01-09'", Parser.replaceProcessing("{d '1999-01-09'}", true, false));
    assertEquals("DATE '1999-01-09'", Parser.replaceProcessing("{D  '1999-01-09'}", true, false));
    assertEquals("TIME '20:00:03'", Parser.replaceProcessing("{t '20:00:03'}", true, false));
    assertEquals("TIME '20:00:03'", Parser.replaceProcessing("{T '20:00:03'}", true, false));
    assertEquals("TIMESTAMP '1999-01-09 20:11:11.123455'", Parser.replaceProcessing("{ts '1999-01-09 20:11:11.123455'}", true, false));
    assertEquals("TIMESTAMP '1999-01-09 20:11:11.123455'", Parser.replaceProcessing("{Ts '1999-01-09 20:11:11.123455'}", true, false));

    assertEquals("user", Parser.replaceProcessing("{fn user()}", true, false));
    assertEquals("cos(1)", Parser.replaceProcessing("{fn cos(1)}", true, false));
    assertEquals("extract(week from DATE '2005-01-24')", Parser.replaceProcessing("{fn week({d '2005-01-24'})}", true, false));

    assertEquals("\"T1\" LEFT OUTER JOIN t2 ON \"T1\".id = t2.id",
            Parser.replaceProcessing("{oj \"T1\" LEFT OUTER JOIN t2 ON \"T1\".id = t2.id}", true, false));

    assertEquals("ESCAPE '_'", Parser.replaceProcessing("{escape '_'}", true, false));

    // nothing should be changed in that case, no valid escape code
    assertEquals("{obj : 1}", Parser.replaceProcessing("{obj : 1}", true, false));
  }

  @Test
  void timestampAddDiffFracSecondIsRejected() throws Exception {
    // SQL_TSI_FRAC_SECOND has no portable size across databases (nanoseconds in ODBC/SQL Server,
    // microseconds in MySQL), so pgjdbc rejects it with an explicit error rather than risk
    // silently producing values off by a factor of 1000. See issue #4086.
    PSQLException add = assertThrows(PSQLException.class,
        () -> Parser.replaceProcessing("{fn timestampadd(SQL_TSI_FRAC_SECOND, ?, {fn now()})}", true, false));
    assertEquals(PSQLState.NOT_IMPLEMENTED.getState(), add.getSQLState());
    assertTrue(add.getMessage().contains("SQL_TSI_FRAC_SECOND"), add.getMessage());

    // timestampdiff is rejected the same way, including the case-insensitive interval name
    PSQLException diff = assertThrows(PSQLException.class,
        () -> Parser.replaceProcessing("{fn timestampdiff(sql_tsi_frac_second, ?, ?)}", true, false));
    assertEquals(PSQLState.NOT_IMPLEMENTED.getState(), diff.getSQLState());
    assertTrue(diff.getMessage().contains("sql_tsi_frac_second"), diff.getMessage());
  }

  @Test
  void modifyJdbcCall() throws SQLException {
    ProtocolVersion protocolVersion = ProtocolVersion.fromMajorMinor(3,0);
    assertEquals("select * from pack_getValue(?) as result", Parser.modifyJdbcCall("{ ? = call pack_getValue}", true, ServerVersion.v9_6.getVersionNum(),
        EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("select * from pack_getValue(?,?)  as result", Parser.modifyJdbcCall("{ ? = call pack_getValue(?) }", true, ServerVersion.v9_6.getVersionNum(),
        EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("select * from pack_getValue(?) as result", Parser.modifyJdbcCall("{ ? = call pack_getValue()}", true, ServerVersion.v9_6.getVersionNum(),
        EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("select * from pack_getValue(?,?,?,?)  as result", Parser.modifyJdbcCall("{ ? = call pack_getValue(?,?,?) }", true, ServerVersion.v9_6.getVersionNum(),
        EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v9_6.getVersionNum(),
        EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v9_6.getVersionNum(),
        EscapeSyntaxCallMode.CALL_IF_NO_RETURN).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v9_6.getVersionNum(),
        EscapeSyntaxCallMode.CALL).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v9_6.getVersionNum(),
        EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v9_6.getVersionNum(),
        EscapeSyntaxCallMode.CALL_IF_NO_RETURN).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v9_6.getVersionNum(),
        EscapeSyntaxCallMode.CALL).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v11.getVersionNum(),
        EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v11.getVersionNum(),
        EscapeSyntaxCallMode.CALL_IF_NO_RETURN).getSql());
    assertEquals("call lower(?,?)", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v11.getVersionNum(),
        EscapeSyntaxCallMode.CALL).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v11.getVersionNum(),
        EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("call lower(?,?)", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v11.getVersionNum(),
        EscapeSyntaxCallMode.CALL_IF_NO_RETURN).getSql());
    assertEquals("call lower(?,?)", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v11.getVersionNum(),
        EscapeSyntaxCallMode.CALL).getSql());
  }

  /**
   * When the single OUT parameter is moved into the function call, a comment between {@code (} and
   * {@code )} is not a real argument, so it must not gain a spurious comma. See issue #2538.
   */
  @Test
  void modifyJdbcCallOutParamWithCommentOnlyArgs() throws SQLException {
    // Comment-only argument list: no comma, otherwise the result would be "f(?, )".
    assertEquals("select * from pack_getValue(?/* no args */) as result",
        Parser.modifyJdbcCall("{ ? = call pack_getValue(/* no args */)}", true,
            ServerVersion.v9_6.getVersionNum(), EscapeSyntaxCallMode.SELECT).getSql());
    // A real argument behind a comment still gets the comma.
    assertEquals("select * from pack_getValue(?,/* c */ ?) as result",
        Parser.modifyJdbcCall("{ ? = call pack_getValue(/* c */ ?)}", true,
            ServerVersion.v9_6.getVersionNum(), EscapeSyntaxCallMode.SELECT).getSql());
  }

  /**
   * A comment after the closing brace of a {@code { ... }} escape must be tolerated rather than
   * rejected as a syntax error, and it must not leak into the rewritten SQL. See issue #2538.
   */
  @Test
  void modifyJdbcCallToleratesTrailingComment() throws SQLException {
    assertEquals("call lower(?,?)", Parser.modifyJdbcCall("{call lower(?,?)} /* trailing */", true,
        ServerVersion.v11.getVersionNum(), EscapeSyntaxCallMode.CALL).getSql());
    assertEquals("call lower(?,?)", Parser.modifyJdbcCall("{ ? = call lower(?)} -- trailing", true,
        ServerVersion.v11.getVersionNum(), EscapeSyntaxCallMode.CALL).getSql());
    assertEquals("select * from lower(?,?) as result",
        Parser.modifyJdbcCall("{call lower(?,?)}\n/* trailing */", true,
            ServerVersion.v9_6.getVersionNum(), EscapeSyntaxCallMode.SELECT).getSql());
    // A trailing token that is not a comment is still a syntax error.
    assertThrows(PSQLException.class, () -> Parser.modifyJdbcCall("{call lower(?,?)} garbage", true,
        ServerVersion.v11.getVersionNum(), EscapeSyntaxCallMode.CALL));
  }

  /**
   * A {@code CALL} (or {@code { ? = call ... }} escape) preceded by a comment must still be
   * recognised as a function call, otherwise OUT parameter registration fails. See issue #2538.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "call test_procedure(?,?)",
      "{ ? = call test_function(?)}",
      "{call test_procedure(?,?)}",
      "/* DeviceTagBatchDAO.generateBatch */ call test_procedure(?,?)",
      "/* some comment */ { ? = call test_function(?)}",
      "/* nested /* comment */ */ call test_procedure(?,?)",
      "  /* leading whitespace */  call test_procedure(?,?)",
      "-- a line comment\ncall test_procedure(?,?)",
      "CALL test_procedure(?,?)",
      "/* mixed case */ CaLl test_procedure(?,?)",
  })
  void callWithLeadingCommentIsFunction(String sql) throws SQLException {
    JdbcCallParseInfo parseInfo = Parser.modifyJdbcCall(sql, true, ServerVersion.v14.getVersionNum(),
        EscapeSyntaxCallMode.CALL);
    assertTrue(parseInfo.isFunction(), () -> "isFunction() should be true for: " + sql);
  }

  /**
   * Statements that are not calls must not be mistaken for function calls, even when a comment
   * happens to contain the word {@code call}.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "select 1",
      "/* call this later */ select 1",
      "-- call test_procedure(?,?)\nselect 1",
      "callme(?)",
  })
  void nonCallIsNotFunction(String sql) throws SQLException {
    JdbcCallParseInfo parseInfo = Parser.modifyJdbcCall(sql, true, ServerVersion.v14.getVersionNum(),
        EscapeSyntaxCallMode.CALL);
    assertFalse(parseInfo.isFunction(), () -> "isFunction() should be false for: " + sql);
  }

  /**
   * The keyword in front of a {@code ;} classifies the statement it ends, not the one after it.
   * RETURNING is reserved but a column label may still spell it, and DELETE ends a CREATE POLICY,
   * so either can be the last word of a valid statement. The flags such a keyword set used to be
   * applied to the next statement, and the UPDATE after it got no RETURNING clause or was
   * reported as a DELETE. The ordinary label is the control and passes on that parser too.
   */
  @ParameterizedTest
  @MethodSource
  void theKeywordBeforeASemicolonDoesNotClassifyTheNextStatement(String sql) throws SQLException {
    NativeQuery update = parse(sql, "id").get(1);
    assertAll(sql,
        () -> assertEquals(SqlCommandType.UPDATE, update.command.getType()),
        () -> assertEquals(" update t set a=1\nRETURNING \"id\"", update.nativeSql));
  }

  static Stream<Arguments> theKeywordBeforeASemicolonDoesNotClassifyTheNextStatement() {
    return Stream.of(
        argumentSet("a column label spelled RETURNING", "select 1 as returning; update t set a=1"),
        argumentSet("CREATE POLICY ending in DELETE", "create policy p on t for delete; update t set a=1"),
        argumentSet("control: an ordinary column label", "select 1 as x; update t set a=1"));
  }

  /**
   * An INSERT is classified as an INSERT, and gets the RETURNING clause that generated keys ask
   * for, wherever it stands in the string. Only an INSERT that started the string used to be
   * recognized; one after any other statement was reported as BLANK and got no RETURNING.
   */
  @ParameterizedTest
  @MethodSource
  void anInsertAfterAnotherStatementGetsReturning(String sql) throws SQLException {
    NativeQuery insert = parse(sql, "id").get(1);
    assertAll(sql,
        () -> assertEquals(SqlCommandType.INSERT, insert.command.getType()),
        () -> assertEquals(" insert into t(a) values($1)\nRETURNING \"id\"", insert.nativeSql),
        () -> assertTrue(insert.command.isReturningKeywordPresent(), "isReturningKeywordPresent"),
        () -> assertFalse(insert.command.isBatchedReWriteCompatible(), "isBatchedReWriteCompatible"));
  }

  static Stream<Arguments> anInsertAfterAnotherStatementGetsReturning() {
    return Stream.of(
        argumentSet("after a SELECT", "select 1; insert into t(a) values(?)"),
        argumentSet("after an INSERT", "insert into t(a) values(1); insert into t(a) values(?)"),
        argumentSet("after an INSERT ending in the keyword VALUES",
            "insert into dv default values; insert into t(a) values(?)"));
  }

  /**
   * An INSERT that starts the string gets RETURNING as well. Passes on the parser before the fix
   * too; it guards the position the old INSERT detection did handle.
   */
  @Test
  void anInsertThatStartsTheStringGetsReturning() throws SQLException {
    NativeQuery insert = parse("insert into t(a) values(?); select 1", "id").get(0);
    assertAll(
        () -> assertEquals(SqlCommandType.INSERT, insert.command.getType()),
        () -> assertEquals("insert into t(a) values($1)\nRETURNING \"id\"", insert.nativeSql));
  }

  /**
   * Batch rewrite applies to the first INSERT of the string only, although every INSERT is
   * classified as an INSERT. Passes on the parser before the fix as well.
   */
  @Test
  void onlyTheFirstInsertOfTheStringIsBatchRewriteCompatible() throws SQLException {
    List<NativeQuery> queries = parse("insert into t(a) values(?); insert into t(a) values(?)");
    assertAll(
        () -> assertTrue(queries.get(0).command.isBatchedReWriteCompatible(), "first INSERT"),
        () -> assertFalse(queries.get(1).command.isBatchedReWriteCompatible(), "second INSERT"));
  }

  /**
   * The last statement keeps its own command type when its last character is a {@code ?}. The
   * placeholder leaves no text after it, and that empty remainder used to be taken for an empty
   * statement. The last statement then took the command type and RETURNING flag of a single
   * statement before it, or no command type after two or more, and got no RETURNING. The two
   * controls, a space after the {@code ?} and a single statement, pass on that parser too.
   */
  @ParameterizedTest
  @MethodSource
  void theLastStatementEndingInAPlaceholderKeepsItsType(String sql, SqlCommandType expectedType,
      String expectedNativeSql) throws SQLException {
    List<NativeQuery> queries = parse(sql, "id");
    NativeQuery last = queries.get(queries.size() - 1);
    assertAll(sql,
        () -> assertEquals(expectedType, last.command.getType()),
        () -> assertEquals(expectedNativeSql, last.nativeSql));
  }

  static Stream<Arguments> theLastStatementEndingInAPlaceholderKeepsItsType() {
    return Stream.of(
        argumentSet("a DELETE after a SELECT", "select 1; delete from t where a=?",
            SqlCommandType.DELETE, " delete from t where a=$1\nRETURNING \"id\""),
        argumentSet("an UPDATE after an UPDATE", "update t set a=1; update t set a=?",
            SqlCommandType.UPDATE, " update t set a=$1\nRETURNING \"id\""),
        argumentSet("a DELETE after two statements", "select 1; select 2; delete from t where a=?",
            SqlCommandType.DELETE, " delete from t where a=$1\nRETURNING \"id\""),
        argumentSet("control: a space after the placeholder", "select 1; delete from t where a=? ",
            SqlCommandType.DELETE, " delete from t where a=$1 \nRETURNING \"id\""),
        argumentSet("control: a single statement", "delete from t where a=?",
            SqlCommandType.DELETE, "delete from t where a=$1\nRETURNING \"id\""));
  }

  /**
   * A {@code ;} ends a statement unless it is inside a BEGIN ATOMIC body. Such a body opens only
   * where CREATE FUNCTION or CREATE PROCEDURE (with or without OR REPLACE) has BEGIN and ATOMIC as
   * adjacent keywords outside parentheses; a comment between the two keeps them adjacent. BEGIN,
   * ATOMIC, FUNCTION, and PROCEDURE are unreserved in PostgreSQL, so elsewhere they name tables,
   * columns, and aliases, and a body detected there used to keep every later statement in one.
   *
   * <p>Every statement is valid PostgreSQL except the two with a token between BEGIN and ATOMIC,
   * which the parser still has to split. On the parser before the fix, the body cases pass,
   * and so do the sequence named BEGIN, the column label BEGIN, and the ORDER BY, each for a
   * reason unrelated to this rule; they guard the checks the fix added.
   */
  @ParameterizedTest
  @MethodSource
  void aSemicolonEndsAStatementUnlessInsideABeginAtomicBody(String sql, int expectedStatements)
      throws SQLException {
    List<NativeQuery> queries = parse(sql);
    assertEquals(expectedStatements, queries.size(),
        () -> "statements of " + sql + ": "
            + queries.stream().map(q -> q.nativeSql).collect(Collectors.toList()));
  }

  static Stream<Arguments> aSemicolonEndsAStatementUnlessInsideABeginAtomicBody() {
    return Stream.of(
        argumentSet("body: function",
            "create function f() returns int language sql begin atomic select 1; end", 1),
        argumentSet("body: or replace function",
            "create or replace function f() returns int language sql begin atomic select 1; end", 1),
        argumentSet("body: procedure",
            "create procedure p() language sql begin atomic select 1; end", 1),
        argumentSet("body: upper case or replace procedure",
            "CREATE OR REPLACE PROCEDURE p() LANGUAGE SQL BEGIN ATOMIC select 1; END", 1),
        argumentSet("body: a comment between BEGIN and ATOMIC",
            "create function f() returns int language sql begin /* c */ atomic select 1; end", 1),
        argumentSet("body: a line comment between BEGIN and ATOMIC",
            "create function f() returns int language sql begin -- c\n atomic select 1; end", 1),
        argumentSet("invalid SQL: a quoted identifier between BEGIN and ATOMIC",
            "create function f() returns int language sql begin \"x\" atomic select 1; end; select 2",
            3),
        argumentSet("invalid SQL: a dollar-quoted string between BEGIN and ATOMIC",
            "create function f() returns int language sql begin $$q$$ atomic select 1; end; select 2",
            3),
        argumentSet("BEGIN and ATOMIC inside a parenthesized RETURN expression",
            "create function g() returns int language sql"
                + " return (select begin atomic from t limit 1); select 2; select 3", 3),
        argumentSet("a sequence named BEGIN, then a function named ATOMIC",
            "create sequence begin;"
                + " create function atomic() returns int language sql as 'select 1'; select 3", 3),
        argumentSet("a column label BEGIN, then a procedure named ATOMIC",
            "create view v as select 1 as begin;"
                + " create procedure atomic() language sql as 'select 1'; select 3", 3),
        argumentSet("a view selecting columns BEGIN and ATOMIC",
            "create view v as select begin, atomic from t; insert into t(a) values(?)", 2),
        argumentSet("a view selecting columns BEGIN and ATOMIC, space before the semicolon",
            "create view v as select begin, atomic from t ; insert into t(a) values(?)", 2),
        argumentSet("CREATE TABLE AS ordered by BEGIN and ATOMIC",
            "create table t2 as select * from t order by begin, atomic; insert into t(a) values(?)",
            2),
        argumentSet("a view aliasing column BEGIN as ATOMIC",
            "create view v as select begin atomic from t; insert into t(a) values(?)", 2),
        argumentSet("CREATE TABLE AS aliasing column BEGIN as ATOMIC",
            "create table t2 as select begin atomic from t; insert into t(a) values(?)", 2),
        argumentSet("a table named BEGIN with a column named ATOMIC",
            "create table begin(atomic int); select 1; select 2", 3),
        argumentSet("a view after a function definition",
            "create function f() returns int language sql as 'select 1';"
                + " create view v as select begin atomic from t; select 2", 3),
        argumentSet("a view with a column alias FUNCTION",
            "create view v as select a function, begin atomic from t; select 1", 2),
        argumentSet("a materialized view named FUNCTION",
            "create materialized view function as select begin atomic from t; select 1", 2),
        argumentSet("a table named PROCEDURE selecting BEGIN as ATOMIC",
            "create table procedure as select begin atomic from t; select 1", 2));
  }

  private static List<NativeQuery> parse(String sql, String... returningColumns)
      throws SQLException {
    return Parser.parseJdbcSql(sql, true, true, true, true, true, returningColumns);
  }

  @Test
  void unterminatedEscape() throws Exception {
    assertEquals("{oj ", Parser.replaceProcessing("{oj ", true, false));
  }

  @Test
  @Disabled(value = "returning in the select clause is hard to distinguish from insert ... returning *")
  void insertSelectFakeReturning() throws SQLException {
    String query =
        "insert test(id, name) select 1, 'value' as RETURNING from test2";
    List<NativeQuery> qry =
        Parser.parseJdbcSql(
            query, true, true, true, true, true);
    boolean returningKeywordPresent = qry.get(0).command.isReturningKeywordPresent();
    assertFalse(returningKeywordPresent, "Query does not have returning clause " + query);
  }

  @Test
  void insertSelectReturning() throws SQLException {
    String query =
        "insert test(id, name) select 1, 'value' from test2 RETURNING id";
    List<NativeQuery> qry =
        Parser.parseJdbcSql(
            query, true, true, true, true, true);
    boolean returningKeywordPresent = qry.get(0).command.isReturningKeywordPresent();
    assertTrue(returningKeywordPresent, "Query has a returning clause " + query);
  }

  @Test
  void insertReturningInWith() throws SQLException {
    String query =
        "with x as (insert into mytab(x) values(1) returning x) insert test(id, name) select 1, 'value' from test2";
    List<NativeQuery> qry =
        Parser.parseJdbcSql(
            query, true, true, true, true, true);
    boolean returningKeywordPresent = qry.get(0).command.isReturningKeywordPresent();
    assertFalse(returningKeywordPresent, "There's no top-level <<returning>> clause " + query);
  }

  @Test
  void insertBatchedReWriteOnConflict() throws SQLException {
    String query = "insert into test(id, name) values (:id,:name) ON CONFLICT (id) DO NOTHING";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true);
    SqlCommand command = qry.get(0).getCommand();
    assertEquals(34, command.getBatchRewriteValuesBraceOpenPosition());
    assertEquals(44, command.getBatchRewriteValuesBraceClosePosition());
  }

  @Test
  void insertBatchedReWriteOnConflictUpdateBind() throws SQLException {
    String query = "insert into test(id, name) values (?,?) ON CONFLICT (id) UPDATE SET name=?";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true);
    SqlCommand command = qry.get(0).getCommand();
    assertFalse(command.isBatchedReWriteCompatible(), "update set name=? is NOT compatible with insert rewrite");
  }

  @Test
  void insertBatchedReWriteOnConflictUpdateConstant() throws SQLException {
    String query = "insert into test(id, name) values (?,?) ON CONFLICT (id) UPDATE SET name='default'";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true);
    SqlCommand command = qry.get(0).getCommand();
    assertTrue(command.isBatchedReWriteCompatible(), "update set name='default' is compatible with insert rewrite");
  }

  @Test
  void insertMultiInsert() throws SQLException {
    String query =
        "insert into test(id, name) values (:id,:name),(:id,:name) ON CONFLICT (id) DO NOTHING";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true);
    SqlCommand command = qry.get(0).getCommand();
    assertEquals(34, command.getBatchRewriteValuesBraceOpenPosition());
    assertEquals(56, command.getBatchRewriteValuesBraceClosePosition());
  }

  @Test
  void valuesTableParse() throws SQLException {
    String query = "insert into values_table (id, name) values (?,?)";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true);
    SqlCommand command = qry.get(0).getCommand();
    assertEquals(43, command.getBatchRewriteValuesBraceOpenPosition());
    assertEquals(49, command.getBatchRewriteValuesBraceClosePosition());

    query = "insert into table_values (id, name) values (?,?)";
    qry = Parser.parseJdbcSql(query, true, true, true, true, true);
    command = qry.get(0).getCommand();
    assertEquals(43, command.getBatchRewriteValuesBraceOpenPosition());
    assertEquals(49, command.getBatchRewriteValuesBraceClosePosition());
  }

  @Test
  void createTableParseWithOnDeleteClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "create table \"testTable\" (\"id\" INT SERIAL NOT NULL PRIMARY KEY, \"foreignId\" INT REFERENCES \"otherTable\" (\"id\") ON DELETE NO ACTION)";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, returningColumns);
    SqlCommand command = qry.get(0).getCommand();
    assertFalse(command.isReturningKeywordPresent(), "No returning keyword should be present");
    assertEquals(SqlCommandType.CREATE, command.getType());
  }

  @Test
  void createTableParseWithOnUpdateClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "create table \"testTable\" (\"id\" INT SERIAL NOT NULL PRIMARY KEY, \"foreignId\" INT REFERENCES \"otherTable\" (\"id\")) ON UPDATE NO ACTION";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, returningColumns);
    SqlCommand command = qry.get(0).getCommand();
    assertFalse(command.isReturningKeywordPresent(), "No returning keyword should be present");
    assertEquals(SqlCommandType.CREATE, command.getType());
  }

  @Test
  void alterTableParseWithOnDeleteClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "alter table \"testTable\" ADD \"foreignId\" INT REFERENCES \"otherTable\" (\"id\") ON DELETE NO ACTION";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, returningColumns);
    SqlCommand command = qry.get(0).getCommand();
    assertFalse(command.isReturningKeywordPresent(), "No returning keyword should be present");
    assertEquals(SqlCommandType.ALTER, command.getType());
  }

  @Test
  void alterTableParseWithOnUpdateClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "alter table \"testTable\" ADD \"foreignId\" INT REFERENCES \"otherTable\" (\"id\") ON UPDATE RESTRICT";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, returningColumns);
    SqlCommand command = qry.get(0).getCommand();
    assertFalse(command.isReturningKeywordPresent(), "No returning keyword should be present");
    assertEquals(SqlCommandType.ALTER, command.getType());
  }

  @Test
  void parseV14functions() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "CREATE OR REPLACE FUNCTION asterisks(n int)\n"
        + "  RETURNS SETOF text\n"
        + "  LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE\n"
        + "BEGIN ATOMIC\n"
        + "SELECT repeat('*', g) FROM generate_series (1, n) g; \n"
        + "END;";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, returningColumns);
    assertNotNull(qry);
    assertEquals(1, qry.size(), "There should only be one query returned here");
  }
}
