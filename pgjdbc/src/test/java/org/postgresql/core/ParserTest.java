/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
   * The END after the last {@code ;} of a BEGIN ATOMIC function body ends the CREATE, so the
   * statement after it is parsed as a statement of its own. The parser used to treat everything
   * after BEGIN ATOMIC as part of the body, and the server then rejected the string with "cannot
   * insert multiple commands into a prepared statement".
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "create function f() returns int language sql begin atomic select 1; select 2; end",
      "create function f() returns int language sql begin atomic select 1; END",
      "create function f() returns int language sql begin atomic select 1; /* c */ end",
      "create function f() returns int language sql begin atomic select 1;\n-- c\nend",
      // END is followed by a comment rather than by the ';'
      "create function f() returns int language sql begin atomic select 1; end /* c */",
      // A quoted name is not a keyword, so no keyword precedes the last ';'
      "create function f() returns int language sql begin atomic select 1 as \"v\"; end",
  })
  void bodyEndsAtTheEndAfterItsLastSemicolon(String create) throws SQLException {
    assertEquals(Arrays.asList(create, " select 42"), nativeSqlOf(create + "; select 42"));
  }

  /**
   * A CASE expression inside a BEGIN ATOMIC body ends with an END of its own, and that END leaves
   * the body open.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "create function f() returns int language sql begin atomic"
          + " select case when true then 1 else 2 end; end",
      "create function f() returns int language sql begin atomic"
          + " select case when true then case when false then 1 else 2 end else 3 end; end",
  })
  void caseExpressionEndLeavesTheBodyOpen(String create) throws SQLException {
    assertEquals(Arrays.asList(create, " select 42"), nativeSqlOf(create + "; select 42"));
  }

  /**
   * END is a reserved keyword, but PostgreSQL accepts it as a column label, so a statement inside a
   * BEGIN ATOMIC body may end on a column labeled {@code end}, with or without AS. Such an END
   * follows a value or AS, never a {@code ;}, and it leaves the body open.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "create function f() returns int language sql begin atomic select 1 as end; end",
      "create function f() returns int language sql begin atomic select 1 end; end",
      // A label inside a CASE expression follows its value or AS like any other label
      "create function f() returns int language sql begin atomic"
          + " select case when (select 1 as end) = 1 then 1 else 2 end; end",
      "create function f() returns int language sql begin atomic"
          + " select case when (select 1 end) = 1 then 1 else 2 end; end",
  })
  void endAsAColumnLabelLeavesTheBodyOpen(String create) throws SQLException {
    assertEquals(Arrays.asList(create, " select 42"), nativeSqlOf(create + "; select 42"));
  }

  /**
   * An empty BEGIN ATOMIC body has no {@code ;}, so its END is the first keyword after the
   * ATOMIC.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "create function f() returns void language sql begin atomic end",
      // END is followed by a comment rather than by the ';'
      "create function f() returns void language sql begin atomic end /* c */",
  })
  void emptyBodyEndsAtTheEndAfterAtomic(String create) throws SQLException {
    assertEquals(Arrays.asList(create, " select 42"), nativeSqlOf(create + "; select 42"));
  }

  /**
   * ATOMIC is an ordinary identifier in PostgreSQL, so a BEGIN ATOMIC body may name a parameter, a
   * column, or a qualified column {@code atomic}. Only the ATOMIC that opens the body makes the
   * next keyword an empty body's END, so the label END after any other {@code atomic} leaves the
   * body open.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "create function f(atomic int) returns int language sql begin atomic"
          + " select atomic end; select 1; end",
      "create function f(atomic int) returns int language sql begin atomic"
          + " select tc.atomic + 1 end from tc; select 1; end",
      "create function f(atomic int) returns int language sql begin atomic"
          + " select 1 as atomic, 2 end; select 2; end",
  })
  void atomicAsAnIdentifierMarksNoEmptyBody(String create) throws SQLException {
    assertEquals(Arrays.asList(create, " select 42"), nativeSqlOf(create + "; select 42"));
  }

  /**
   * A BEGIN ATOMIC body whose first statement is empty puts the {@code ;} right after ATOMIC, and
   * that {@code ;} belongs to the body. The parser used to split the CREATE there, and the server
   * rejected the first part with "syntax error at end of input".
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "create procedure p() language sql begin atomic; select 1; end",
      "create procedure p() language sql begin atomic; end",
      // ATOMIC ends at the space, so the ';' is handled after it as usual
      "create procedure p() language sql begin atomic ; select 1; end",
  })
  void semicolonRightAfterAtomicStaysInTheBody(String create) throws SQLException {
    assertEquals(Arrays.asList(create, " select 42"), nativeSqlOf(create + "; select 42"));
  }

  /**
   * The end of one BEGIN ATOMIC body leaves no state behind, so a second CREATE with its own body
   * is split off at its own END.
   */
  @Test
  void consecutiveBeginAtomicFunctionsAreSplitApart() throws SQLException {
    assertEquals(
        Arrays.asList(
            "create function f() returns int language sql begin atomic select 1; end",
            " create function g() returns int language sql begin atomic select 2; end",
            " select 42"),
        nativeSqlOf(
            "create function f() returns int language sql begin atomic select 1; end;"
                + " create function g() returns int language sql begin atomic select 2; end;"
                + " select 42"));
  }

  /**
   * BEGIN is an ordinary identifier in PostgreSQL, so a statement may end on one. That BEGIN does
   * not pair with an {@code atomic;} in a later statement, and every {@code ;} after it splits.
   * PostgreSQL accepts each string as the statements listed.
   */
  @ParameterizedTest
  @MethodSource("statementsAfterABeginThatOpensNoBody")
  void beginFromAnEarlierStatementOpensNoBody(String sql, List<String> statements)
      throws SQLException {
    assertEquals(statements, nativeSqlOf(sql));
  }

  static Stream<Arguments> statementsAfterABeginThatOpensNoBody() {
    return Stream.of(
        argumentSet("column named begin",
            "create index i on t (begin); select 1 as atomic; select 2",
            Arrays.asList("create index i on t (begin)", " select 1 as atomic", " select 2")),
        argumentSet("table named begin",
            "create table begin(); select 1 as atomic; select 2",
            Arrays.asList("create table begin()", " select 1 as atomic", " select 2")),
        argumentSet("table named begin, atomic two statements later",
            "create table begin(); select 1; select 2 as atomic; select 3",
            Arrays.asList("create table begin()", " select 1", " select 2 as atomic", " select 3")));
  }

  /**
   * BEGIN ATOMIC opens a function body only when nothing but whitespace and comments separates the
   * two words. Neither is reserved in PostgreSQL, so a CREATE may use both as names, and when code
   * separates them, a {@code ;} after them ends the statement.
   */
  @ParameterizedTest
  @MethodSource("beginAndAtomicSeparatedByCode")
  void beginAndAtomicSeparatedByCodeOpenNoBody(String sql, List<String> statements)
      throws SQLException {
    assertEquals(statements, nativeSqlOf(sql));
  }

  static Stream<Arguments> beginAndAtomicSeparatedByCode() {
    return Stream.of(
        argumentSet("aliases separated by a parenthesis, atomic before the ';'",
            "create view v as select * from (select 1 as begin) atomic; select 42",
            Arrays.asList("create view v as select * from (select 1 as begin) atomic",
                " select 42")),
        argumentSet("table begin with a column atomic",
            "create table begin(atomic int); select 42",
            Arrays.asList("create table begin(atomic int)", " select 42")),
        argumentSet("a comment between begin and atomic still opens a body",
            "create function f() returns int language sql begin /* c */ atomic select 1; end;"
                + " select 42",
            Arrays.asList(
                "create function f() returns int language sql begin /* c */ atomic select 1; end",
                " select 42")));
  }

  /**
   * A BEGIN ATOMIC body that never reaches its END keeps every {@code ;} after it, since each of
   * them may separate the statements of the body.
   */
  @Test
  void bodyWithoutEndKeepsEverySemicolon() throws SQLException {
    String sql = "create function f() returns int language sql begin atomic select 1; select 2";
    assertEquals(Collections.singletonList(sql), nativeSqlOf(sql));
  }

  /**
   * CASE is a reserved keyword, but PostgreSQL accepts it as a column label, so a statement inside
   * a BEGIN ATOMIC body may name a column {@code case}. The body still ends at the END after its
   * last {@code ;}, because the parser does not pair CASE with END.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "create function f() returns int language sql begin atomic select 1 as case; end",
      "create function f() returns int language sql begin atomic select 1 case; end",
      "create function f() returns int language sql begin atomic select t.case from t; end",
  })
  void caseAsAColumnLabelDoesNotHideTheBodyEnd(String create) throws SQLException {
    assertEquals(Arrays.asList(create, " select 42"), nativeSqlOf(create + "; select 42"));
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

  /**
   * Returns the {@code nativeSql} of each statement the parser splits {@code sql} into, so a
   * failed assertion shows where the split fell.
   */
  private static List<String> nativeSqlOf(String sql) throws SQLException {
    List<String> statements = new ArrayList<>();
    for (NativeQuery query : Parser.parseJdbcSql(sql, true, true, true, true, true)) {
      statements.add(query.nativeSql);
    }
    return statements;
  }
}
