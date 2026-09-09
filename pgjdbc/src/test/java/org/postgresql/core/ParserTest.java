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

import org.postgresql.jdbc.EscapeSyntaxCallMode;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.List;

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
   * The END of a BEGIN ATOMIC function body closes it, so statements after the CREATE are split
   * again. They used to be glued to it, and the server answered "cannot insert multiple commands
   * into a prepared statement".
   */
  @Test
  void beginAtomicBodyEndsAtItsEnd() throws SQLException {
    String body = "create function f() returns int language sql begin atomic select 1; select 2; end";
    List<NativeQuery> qry =
        Parser.parseJdbcSql(body + "; select 42", true, true, true, true, true);
    assertEquals(2, qry.size());
    assertEquals(body, qry.get(0).nativeSql);
    assertEquals(" select 42", qry.get(1).nativeSql);

    // A body whose last statement ends in a bare END label still splits after the real one
    assertEquals(2, Parser.parseJdbcSql(
        "create function f() returns int language sql begin atomic select 1 end; end; select 42",
        true, true, true, true, true).size());

    // The keyword is matched regardless of case, and a space before the ';' changes nothing
    assertEquals(2, Parser.parseJdbcSql(
        "create function f() returns int language sql begin atomic select 1; END; select 42",
        true, true, true, true, true).size());
    assertEquals(2, Parser.parseJdbcSql(
        "create function f() returns int language sql begin atomic select 1; end ; select 42",
        true, true, true, true, true).size());
  }

  /**
   * A CASE expression inside the body ends with its own END, which must not be mistaken for the
   * body's. PostgreSQL accepts the function below, so the whole CREATE has to stay one statement.
   */
  @Test
  void caseInsideBeginAtomicKeepsItsOwnEnd() throws SQLException {
    for (String select : new String[]{
        "select case when true then 1 else 2 end",
        "select case when true then case when false then 1 else 2 end else 3 end"}) {
      String sql = "create function f() returns int language sql begin atomic " + select
          + "; end; select 42";
      List<NativeQuery> qry = Parser.parseJdbcSql(sql, true, true, true, true, true);
      assertEquals(2, qry.size(), sql);
      assertEquals(" select 42", qry.get(1).nativeSql, sql);
    }
  }

  /**
   * END is not reserved as a column label in PostgreSQL, so a body may name a column {@code end},
   * with or without AS. The body's own END always follows a {@code ;}, which is what tells them
   * apart. Every statement below is one CREATE FUNCTION as far as the grammar is concerned.
   *
   * <p>These pass on the unfixed parser too, which never closes a body at all. They guard the
   * closing rule against being too eager rather than reproducing the defect.</p>
   */
  @Test
  void endAsAColumnLabelDoesNotEndTheBody() throws SQLException {
    assertSingleStatement("create function period_of() returns table(start date, \"end\" date)"
        + " language sql begin atomic select lo as start, hi as end from periods; end");
    assertSingleStatement(
        "create function f() returns int language sql begin atomic select lo as end; end");
    // A bare label, with no AS in front of it
    assertSingleStatement(
        "create function f() returns int language sql begin atomic select 1 end; end");
    assertSingleStatement(
        "create function f() returns int language sql begin atomic select 1 end; select 2; end");
  }

  /**
   * An empty body has no {@code ;} before its END, so the ATOMIC in front of it is what marks the
   * END as the body's. The single-statement half passes on the unfixed parser; only the split
   * assertion reproduces the defect.
   */
  @Test
  void emptyBeginAtomicBody() throws SQLException {
    assertSingleStatement(
        "create function f() returns void language sql begin atomic end");
    assertEquals(2, Parser.parseJdbcSql(
        "create function f() returns void language sql begin atomic end; select 42",
        true, true, true, true, true).size());
  }

  /**
   * The label and the terminator have to be told apart in the awkward positions too: a label
   * inside a CASE, which also has to leave the CASE nesting balanced, and a quoted alias just
   * before the terminator, which is not a keyword and so leaves no keyword behind it. The
   * single-statement half of each pair passes on the unfixed parser; the split half does not.
   */
  @Test
  void endLabelInAwkwardPositions() throws SQLException {
    String[] bodies = {
        "select case when (select 1 as end) = 1 then 1 else 2 end;",
        "select case when (select 1 end) = 1 then 1 else 2 end;",
        "select 1 as \"v\";",
        "select * from (select 1) as \"t\";",
    };
    for (String body : bodies) {
      String create =
          "create function f() returns int language sql begin atomic " + body + " end";
      assertSingleStatement(create);
      assertEquals(2, Parser.parseJdbcSql(create + "; select 42", true, true, true, true, true)
          .size(), create);
    }
  }

  /**
   * ATOMIC is an ordinary identifier in PostgreSQL, so a body may hold a column, a parameter or a
   * qualified name spelled {@code atomic}. Only the ATOMIC that opened the body can mark the very
   * next keyword as an empty body's END. The single-statement half passes on the unfixed parser;
   * only the split assertion reproduces the defect.
   */
  @Test
  void atomicAsAnIdentifierInsideTheBody() throws SQLException {
    String[] bodies = {
        "select atomic end; select 1;",
        "select tc.atomic + 1 end from tc; select 1;",
        "select 1 as atomic, 2 end; select 2;",
        "select case when (select 1 end) = 1 then 1 else tc.atomic end from tc; select 1;",
    };
    for (String body : bodies) {
      String create =
          "create function f(atomic int) returns int language sql begin atomic " + body + " end";
      assertSingleStatement(create);
      assertEquals(2, Parser.parseJdbcSql(create + "; select 42", true, true, true, true, true)
          .size(), create);
    }
  }

  /**
   * A body whose first statement is empty writes the {@code ;} directly against the ATOMIC.
   * PostgreSQL accepts that, and the separator is handled before the keyword in front of it, so
   * the ATOMIC has to be recognised in both places just as the closing END is.
   */
  @Test
  void semicolonDirectlyAfterAtomic() throws SQLException {
    assertSingleStatement("create procedure p() language sql begin atomic; select 1; end");
    assertSingleStatement("create procedure p() language sql begin atomic; end");
    assertEquals(2, Parser.parseJdbcSql(
        "create procedure p() language sql begin atomic; select 1; end; select 42",
        true, true, true, true, true).size());
    // A space in front of the ';' takes the other path to the same answer
    assertSingleStatement("create procedure p() language sql begin atomic ; select 1; end");
  }

  /**
   * BEGIN is an ordinary identifier too, and a statement can end on one. That pending BEGIN
   * belongs to the statement that wrote it, so it must not make the next statement's {@code
   * atomic} look like the opening of a function body.
   */
  @Test
  void beginLeftPendingByAnEarlierStatement() throws SQLException {
    // PostgreSQL 16 accepts every statement below. None puts a keyword ATOMIC directly after a
    // keyword BEGIN, which is what caseAsAColumnLabelKeepsTheBodyOpen's sibling case does.
    assertEquals(3, Parser.parseJdbcSql(
        "create index i on t (begin); select 1 as atomic; select 2",
        true, true, true, true, true).size());
    assertEquals(3, Parser.parseJdbcSql(
        "create table begin(); select 1 as atomic; select 2",
        true, true, true, true, true).size());
    assertEquals(4, Parser.parseJdbcSql(
        "create table begin(); select 1; select 2 as atomic; select 3",
        true, true, true, true, true).size());
    // A body that opened with "begin atomic;" must not leave one pending either
    assertEquals(3, Parser.parseJdbcSql(
        "create function f() returns void language sql begin atomic; end;"
            + " select 1 as atomic; select 2", true, true, true, true, true).size());
    assertEquals(3, Parser.parseJdbcSql(
        "create function f() returns int language sql begin atomic select 1; end;"
            + " select 1 as atomic; select 2", true, true, true, true, true).size());
  }

  /**
   * The one shape the closing rule does not recognise. CASE is an ordinary column label in
   * PostgreSQL, and a body that labels a column that way inflates the nesting count, so the
   * body's END is taken for a CASE end and the body never closes.
   *
   * <p>The unfixed parser does the same, so this is a hole the change leaves open rather than one
   * it opens. It is pinned so a later change to the closing rule shows up here.</p>
   */
  @Test
  void caseAsAColumnLabelKeepsTheBodyOpen() throws SQLException {
    assertSingleStatement(
        "create function f() returns int language sql begin atomic select 1 as case; end;"
            + " select 42");
  }

  private static void assertSingleStatement(String sql) throws SQLException {
    assertEquals(1, Parser.parseJdbcSql(sql, true, true, true, true, true).size(), sql);
  }

  /**
   * A body that is never closed still swallows the rest of the string, which is what the ';' inside
   * it needs. This one passes on the unfixed code too, and is here to pin that it stays that way.
   */
  @Test
  void beginAtomicWithoutEndKeepsSwallowingSemicolons() throws SQLException {
    assertEquals(1, Parser.parseJdbcSql(
        "create function f() returns int language sql begin atomic select 1; select 2",
        true, true, true, true, true).size());
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
