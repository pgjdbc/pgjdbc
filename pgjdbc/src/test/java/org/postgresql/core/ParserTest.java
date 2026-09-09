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
   * Splitting must not move a statement's own classification onto its neighbour. The keyword in
   * front of a {@code ;} used to be examined only after the split had already reset the flags, so
   * a statement ending in {@code returning} or {@code values} handed them to the next statement.
   * Neither word is reserved in PostgreSQL, so both are legal table names.
   */
  @Test
  void statementFlagsDoNotLeakAcrossSemicolon() throws SQLException {
    // "values" is not reserved in PostgreSQL, so a statement really can end on that keyword
    assertSplitMatchesStandalone("insert into dv default values", "insert into t(a) values(?)",
        "id");
    assertSplitMatchesStandalone("select * from values", "insert into t(a) values(?)", "id");
    assertSplitMatchesStandalone("insert into t(a) values(1)", "insert into t(a) values(2)", "id");
    assertSplitMatchesStandalone("select * from returning", "update t set a=1", "id");
    // A statement whose last character is the bind placeholder leaves nothing to append, which
    // used to be read as an empty trailing statement
    assertSplitMatchesStandalone("select * from x", "delete from t where a=?", "id");
    assertSplitMatchesStandalone("select * from x", "update t set a=?", "id");
    // The same pairs with no returning columns to add
    assertSplitMatchesStandalone("insert into dv default values", "insert into t(a) values(?)");
    assertSplitMatchesStandalone("select * from values", "insert into t(a) values(?)");
  }

  /**
   * Asserts that parsing {@code first + "; " + second} classifies each half exactly as parsing that
   * half on its own does.
   */
  private static void assertSplitMatchesStandalone(String first, String second,
      String... returningColumns) throws SQLException {
    List<NativeQuery> split = Parser.parseJdbcSql(
        first + "; " + second, true, true, true, true, true, returningColumns);
    assertEquals(2, split.size(), first + "; " + second);

    String[] halves = {first, second};
    for (int i = 0; i < halves.length; i++) {
      List<NativeQuery> alone =
          Parser.parseJdbcSql(halves[i], true, true, true, true, true, returningColumns);
      assertEquals(1, alone.size(), halves[i]);
      assertEquals(alone.get(0).nativeSql, split.get(i).nativeSql.trim(),
          "native SQL of <" + halves[i] + "> parsed alone and as part of a split");
      assertEquals(alone.get(0).command.getType(), split.get(i).command.getType(),
          "command type of <" + halves[i] + ">");
      assertEquals(alone.get(0).command.isReturningKeywordPresent(),
          split.get(i).command.isReturningKeywordPresent(),
          "returning flag of <" + halves[i] + ">");
      if (i == 0) {
        // Batch rewrite is deliberately position-dependent: SqlCommand rejects any statement with
        // a prior query. Only the first statement can match its standalone parse.
        assertEquals(alone.get(0).command.isBatchedReWriteCompatible(),
            split.get(i).command.isBatchedReWriteCompatible(),
            "batch rewrite of <" + halves[i] + ">");
        assertEquals(alone.get(0).command.getBatchRewriteValuesBraceOpenPosition(),
            split.get(i).command.getBatchRewriteValuesBraceOpenPosition(),
            "values brace open of <" + halves[i] + ">");
        assertEquals(alone.get(0).command.getBatchRewriteValuesBraceClosePosition(),
            split.get(i).command.getBatchRewriteValuesBraceClosePosition(),
            "values brace close of <" + halves[i] + ">");
      } else {
        assertFalse(split.get(i).command.isBatchedReWriteCompatible(),
            "only the first statement may be batch-rewritten");
      }
    }
  }

  /**
   * BEGIN and ATOMIC are ordinary identifiers in PostgreSQL, so a CREATE that is not a routine
   * definition may name a column, a table or an alias after either of them. Only a CREATE
   * FUNCTION or CREATE PROCEDURE can hold a BEGIN ATOMIC body, and the two words have to be
   * adjacent keywords in it.
   *
   * <p>The unfixed parser takes any two consecutive keywords after any CREATE, so most of these
   * glue the rest of the string to the CREATE. Three escape it for reasons of their own: the
   * {@code ;} landing right after the second word, a type name standing between the two, and the
   * keyword naming the object consuming the pending BEGIN before the next statement's name is
   * reached. Those three are guards rather than reproductions.</p>
   */
  @Test
  void beginAndAtomicOutsideARoutineDefinition() throws SQLException {
    // A BEGIN left pending by one statement must not reach the routine name of the next one
    for (String sql : new String[]{
        "create sequence begin; create function atomic() returns int language sql"
            + " as 'select 1'; select 3",
        "create view v as select 1 as begin; create procedure atomic() language sql"
            + " as 'select 1'; select 3"}) {
      assertEquals(3, Parser.parseJdbcSql(sql, true, true, true, true, true).size(), sql);
    }
    // A token that is not a keyword between the two words means they are not adjacent. Neither
    // spelling is valid SQL; they are here because the parser has to reject them anyway.
    for (String sql : new String[]{
        "create function f() returns int language sql begin \"x\" atomic select 1; end; select 2",
        "create function f() returns int language sql begin $$q$$ atomic select 1; end; select 2"}) {
      assertEquals(3, Parser.parseJdbcSql(sql, true, true, true, true, true).size(), sql);
    }
    // A body starts at the statement level, so an adjacent pair inside an expression is not one
    assertEquals(3, Parser.parseJdbcSql(
        "create function g() returns int language sql return (select begin atomic from zz limit 1);"
            + " select 2; select 3", true, true, true, true, true).size());
    for (String sql : new String[]{
        "create view v as select begin, atomic; insert into t(a) values(?)",
        "create view v as select begin, atomic ; insert into t(a) values(?)",
        "create table t2 as select * from zz order by begin, atomic; insert into t(a) values(?)",
        // atomic as the implicit alias of a column named begin
        "create view v as select begin atomic from t; insert into t2 values(?)",
        "create table t2 as select begin atomic from t; insert into t3 values(?)"}) {
      assertEquals(2, Parser.parseJdbcSql(sql, true, true, true, true, true).size(), sql);
    }
    assertEquals(3, Parser.parseJdbcSql(
        "create table begin(atomic int); select 1; select 2",
        true, true, true, true, true).size());
    // The routine that defined one statement does not make the next statement's CREATE a routine
    assertEquals(3, Parser.parseJdbcSql(
        "create function f() returns int language sql as 'select 1';"
            + " create view v as select begin atomic from t; select 2",
        true, true, true, true, true).size());
    // FUNCTION and PROCEDURE are identifiers too, so only the one naming what the CREATE creates
    // counts, whatever the query behind it says
    for (String sql : new String[]{
        "create view v as select x function from t where t.begin atomic; select 1",
        "create materialized view function as select begin atomic from t; select 1",
        "create table procedure(begin int, atomic int); select 1"}) {
      assertEquals(2, Parser.parseJdbcSql(sql, true, true, true, true, true).size(), sql);
    }
  }

  /**
   * A genuine BEGIN ATOMIC body is still recognised, in either kind of routine, in any case, and
   * with a comment between the two keywords.
   */
  @Test
  void beginAtomicInARoutineDefinition() throws SQLException {
    for (String sql : new String[]{
        "create function f() returns int language sql begin atomic select 1; select 2",
        "create or replace function f() returns int language sql begin atomic select 1; select 2",
        "create procedure p() language sql begin atomic select 1; select 2",
        "CREATE FUNCTION f() RETURNS int LANGUAGE SQL BEGIN ATOMIC select 1; select 2",
        "CREATE OR REPLACE PROCEDURE p() LANGUAGE SQL BEGIN ATOMIC select 1; select 2",
        "create function f() returns int language sql begin /* c */ atomic select 1; select 2"}) {
      assertEquals(1, Parser.parseJdbcSql(sql, true, true, true, true, true).size(), sql);
    }
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
