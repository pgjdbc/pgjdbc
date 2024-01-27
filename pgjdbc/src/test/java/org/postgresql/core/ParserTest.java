/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.jdbc.EscapeSyntaxCallMode;
import org.postgresql.jdbc.PlaceholderStyle;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
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

  @Test
  public void testDoubleQuestionmark() throws SQLException {
    List<NativeQuery> qry =
        Parser.parseJdbcSql(
            "SELECT ??", true, true, true, true, true, PlaceholderStyle.NONE);
    assertEquals("SELECT ?", qry.get(0).nativeSql);
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

  @Test
  public void testReplaceProcessingDisabled() throws Exception {
    final String sql = "testString";
    assertSame(
        "The output string must be exactly the input string when replaceProcessingEnabled = false",
        sql, Parser.replaceProcessing(sql, false, false));
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
  public void testSyntaxError() throws Exception {
    final String sql = "SELECT a FROM t WHERE (1 > 0)) ORDER BY a";
    assertEquals("extracted from comments in replaceProcessingreplaceProcessing", sql, Parser.replaceProcessing(sql, true, false));
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
  void modifyJdbcCall() throws SQLException {
    assertEquals("select * from pack_getValue(?) as result", Parser.modifyJdbcCall("{ ? = call pack_getValue}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("select * from pack_getValue(?,?)  as result", Parser.modifyJdbcCall("{ ? = call pack_getValue(?) }", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("select * from pack_getValue(?) as result", Parser.modifyJdbcCall("{ ? = call pack_getValue()}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("select * from pack_getValue(?,?,?,?)  as result", Parser.modifyJdbcCall("{ ? = call pack_getValue(?,?,?) }", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.CALL_IF_NO_RETURN).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.CALL).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.CALL_IF_NO_RETURN).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v9_6.getVersionNum(), 3, EscapeSyntaxCallMode.CALL).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.CALL_IF_NO_RETURN).getSql());
    assertEquals("call lower(?,?)", Parser.modifyJdbcCall("{ ? = call lower(?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.CALL).getSql());
    assertEquals("select * from lower(?,?) as result", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.SELECT).getSql());
    assertEquals("call lower(?,?)", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.CALL_IF_NO_RETURN).getSql());
    assertEquals("call lower(?,?)", Parser.modifyJdbcCall("{call lower(?,?)}", true, ServerVersion.v11.getVersionNum(), 3, EscapeSyntaxCallMode.CALL).getSql());
  }

  @Test
  void unterminatedEscape() throws Exception {
    assertEquals("{oj ", Parser.replaceProcessing("{oj ", true, false));
  }

  @Test
  public void testUnterminatedDollar() throws Exception {
    try {
      Parser.replaceProcessing("$$", true, false);
      fail("Nothing was thrown!");
    } catch (PSQLException e) {
      assertEquals("Unterminated dollar quote started at position 0 in SQL $$. Expected terminating $$", e.getMessage());
    }

    try {
      Parser.replaceProcessing("$$$", true, false);
      fail("Nothing was thrown!");
    } catch (PSQLException e) {
      assertEquals("Unterminated dollar quote started at position 0 in SQL $$$. Expected terminating $$", e.getMessage());
    }
  }

  @Test
  @Disabled(value = "returning in the select clause is hard to distinguish from insert ... returning *")
  void insertSelectFakeReturning() throws SQLException {
    String query =
        "insert test(id, name) select 1, 'value' as RETURNING from test2";
    List<NativeQuery> qry =
        Parser.parseJdbcSql(
            query, true, true, true, true, true, PlaceholderStyle.NONE);
    boolean returningKeywordPresent = qry.get(0).command.isReturningKeywordPresent();
    assertFalse(returningKeywordPresent, "Query does not have returning clause " + query);
  }

  @Test
  void insertSelectReturning() throws SQLException {
    String query =
        "insert test(id, name) select 1, 'value' from test2 RETURNING id";
    List<NativeQuery> qry =
        Parser.parseJdbcSql(
            query, true, true, true, true, true, PlaceholderStyle.NONE);
    boolean returningKeywordPresent = qry.get(0).command.isReturningKeywordPresent();
    assertTrue(returningKeywordPresent, "Query has a returning clause " + query);
  }

  @Test
  public void namedPlaceholderComposite() throws SQLException {

    String query = "SELECT :a; SELECT :b";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, false, true, PlaceholderStyle.ANY);
    assertEquals(2, qry.size());

    NativeQuery nativeQuery;
    nativeQuery = qry.get(0);
    assertEquals(1, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(1, nativeQuery.parameterCtx.nativeParameterCount());
    assertEquals("a", nativeQuery.parameterCtx.getPlaceholderName(0));

    nativeQuery = qry.get(1);
    assertEquals(1, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(1, nativeQuery.parameterCtx.nativeParameterCount());
    assertEquals("b", nativeQuery.parameterCtx.getPlaceholderName(0));
  }

  @Test
  public void namedPlaceholderSimple() throws SQLException {
    String strSQL;
    NativeQuery nativeQuery;

    // Basic
    strSQL = "SELECT :PARAM";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(1, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(1, nativeQuery.parameterCtx.nativeParameterCount());
    assertEquals("PARAM", nativeQuery.parameterCtx.getPlaceholderName(0));

    // Something with a CAST in it
    strSQL = "SELECT :PARAM::boolean";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(1, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(1, nativeQuery.parameterCtx.nativeParameterCount());
    assertEquals("PARAM", nativeQuery.parameterCtx.getPlaceholderName(0));

    // Something with a CAST but no placeholders
    strSQL = "SELECT '{}'::int[]";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(0, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(0, nativeQuery.parameterCtx.nativeParameterCount());

    strSQL = "insert into test_logic_table\n"
        + "  select id, md5(random()::text) as name from generate_series(1, 200000) as id";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(0, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(0, nativeQuery.parameterCtx.nativeParameterCount());

    // We can also do this
    strSQL = "SELECT $1";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(1, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(1, nativeQuery.parameterCtx.nativeParameterCount());

    // But this would be bad syntax
    strSQL = "SELECT :$1";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.NATIVE).get(0);
    assertEquals(1, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(1, nativeQuery.parameterCtx.nativeParameterCount());

    // This is okay, but ugly
    strSQL = "SELECT :$1";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(1, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(1, nativeQuery.parameterCtx.nativeParameterCount());
    assertEquals("$1", nativeQuery.parameterCtx.getPlaceholderName(0));

    // This is ok, as a string
    strSQL = "SELECT $$PARAM$$";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(0, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(0, nativeQuery.parameterCtx.nativeParameterCount());

    strSQL = "SELECT :$$PARAM$$";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(1, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(1, nativeQuery.parameterCtx.nativeParameterCount());
    assertEquals("$$PARAM$$", nativeQuery.parameterCtx.getPlaceholderName(0));

    // Comments must end the capture of a placeholder name
    strSQL = "SELECT :param--Lovely";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(1, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(1, nativeQuery.parameterCtx.nativeParameterCount());
    assertEquals("param", nativeQuery.parameterCtx.getPlaceholderName(0));

    // Placeholder names must not be captured inside comments
    strSQL = "SELECT a--:param";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(0, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(0, nativeQuery.parameterCtx.nativeParameterCount());

    // Or block comments
    strSQL = "SELECT :paramA, /* "
        + ":NotAPlaceholder,"
        + "*/:paramB";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(2, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(2, nativeQuery.parameterCtx.nativeParameterCount());
    assertEquals("paramA", nativeQuery.parameterCtx.getPlaceholderName(0));
    assertEquals("paramB", nativeQuery.parameterCtx.getPlaceholderName(1));

    // Placeholder names must not start with a number
    strSQL = "SELECT :1param";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(0, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(0, nativeQuery.parameterCtx.nativeParameterCount());

    // Native Placeholders must start with a number
    strSQL = "SELECT €param";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(0, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(0, nativeQuery.parameterCtx.nativeParameterCount());

    // Native Placeholders must be all positive numbers, greater than 0
    strSQL = "SELECT $0";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(0, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(0, nativeQuery.parameterCtx.nativeParameterCount());

    strSQL = "SELECT $-1";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(0, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(0, nativeQuery.parameterCtx.nativeParameterCount());

    // Review comment
    strSQL = "select * from foo where name like ':foo'";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(0, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(0, nativeQuery.parameterCtx.nativeParameterCount());
  }

  @Test
  public void namedPlaceholderComplex() throws SQLException {
    String strSQL;
    NativeQuery nativeQuery;

    // CREATE a FUNCTION
    strSQL = "CREATE FUNCTION test_parser(p bigint) RETURNS bigint AS $$ DECLARE v int; BEGIN v := 2*p; RETURN v; END $$ LANGUAGE plpgsql";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(0, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(0, nativeQuery.parameterCtx.nativeParameterCount());

    // CREATE a FUNCTION with unnamed parameters
    strSQL = "CREATE FUNCTION test_parser(bigint) RETURNS bigint AS $$ DECLARE v int; BEGIN v := 2*$1; RETURN v; END $$ LANGUAGE plpgsql";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(0, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(0, nativeQuery.parameterCtx.nativeParameterCount());

    // SELECT from FUNCTION assigning parameters
    strSQL = "SELECT func(p1 := 'x', p2 := 'y')";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(0, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(0, nativeQuery.parameterCtx.nativeParameterCount());

    // SELECT from FUNCTION assigning parameters in another style
    strSQL = "SELECT func(p1 => 'x', p2 => 'y' )";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(0, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(0, nativeQuery.parameterCtx.nativeParameterCount());

    // PREPARE a value statement
    strSQL = "PREPARE prep_values(bigint) AS VALUES($1)";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(0, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(0, nativeQuery.parameterCtx.nativeParameterCount());

    // CREATE a function that does a PREPARE
    strSQL = "CREATE FUNCTION test_parser_execute_prepared() RETURNS VOID AS $$ BEGIN EXECUTE 'PREPARE prep_values(bigint) AS VALUES($1)'; END $$ \n LANGUAGE plpgsql";
    nativeQuery = Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY).get(0);
    assertEquals(strSQL, nativeQuery.nativeSql);
    assertEquals(0, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(0, nativeQuery.parameterCtx.nativeParameterCount());

    // Maybe some day we will be able to do this. Right now it doesn't make sense.
    strSQL = ""
        + "WITH\n"
        + "  FUNCTION test_func_1(p1 bigint, p2 bigint) RETURNS bigint AS $$ BEGIN return $1*$2;\n"
        + "END $$ LANGUAGE plpgsql,\n"
        + "  FUNCTION test_func_2(p1 bigint, p2 bigint) RETURNS bigint AS $$ SELECT $1*$2; $$\n"
        + "LANGUAGE sql\n"
        + "SELECT\n"
        + "  test_func_1( p1 => r.col, :p2),\n"
        + "  test_func_2(r.col, p2 => :p3)\n"
        + "FROM\n"
        + "  some_table r\n"
        + "WHERE\n"
        + "  r.col = :p1\n";

    final List<NativeQuery> nativeQueries =
        Parser.parseJdbcSql(strSQL, true, true, true, false, true, PlaceholderStyle.ANY);
    nativeQuery = nativeQueries.get(0);
    assertEquals(strSQL
                  .replaceAll(":p1", "\\$3")
                  .replaceAll(":p2", "\\$1")
                  .replaceAll(":p3", "\\$2"),
        nativeQuery.nativeSql);
    assertEquals(3, nativeQuery.parameterCtx.placeholderCount());
    assertEquals(3, nativeQuery.parameterCtx.nativeParameterCount());

    List<String> expectedParameterNames = new ArrayList<String>();
    expectedParameterNames.add("p2");
    expectedParameterNames.add("p3");
    expectedParameterNames.add("p1");
    assertEquals(nativeQuery.parameterCtx.getPlaceholderNames(), expectedParameterNames);
  }

  @Test
  void insertReturningInWith() throws SQLException {
    String query =
        "with x as (insert into mytab(x) values(1) returning x) insert test(id, name) select 1, 'value' from test2";
    List<NativeQuery> qry =
        Parser.parseJdbcSql(
            query, true, true, true, true, true, PlaceholderStyle.ANY);
    boolean returningKeywordPresent = qry.get(0).command.isReturningKeywordPresent();
    assertFalse(returningKeywordPresent, "There's no top-level <<returning>> clause " + query);
  }

  @Test
  void insertBatchedReWriteOnConflict() throws SQLException {
    String query = "insert into test(id, name) values (:id,:name) ON CONFLICT (id) DO NOTHING";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, PlaceholderStyle.ANY);
    SqlCommand command = qry.get(0).getCommand();
    assertEquals(34, command.getBatchRewriteValuesBraceOpenPosition());
    assertEquals(44, command.getBatchRewriteValuesBraceClosePosition());
  }

  @Test
  void insertBatchedReWriteOnConflictUpdateBind() throws SQLException {
    String query = "insert into test(id, name) values (?,?) ON CONFLICT (id) UPDATE SET name=?";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, PlaceholderStyle.ANY);
    SqlCommand command = qry.get(0).getCommand();
    assertFalse(command.isBatchedReWriteCompatible(), "update set name=? is NOT compatible with insert rewrite");
  }

  @Test
  void insertBatchedReWriteOnConflictUpdateConstant() throws SQLException {
    String query = "insert into test(id, name) values (?,?) ON CONFLICT (id) UPDATE SET name='default'";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, PlaceholderStyle.NONE);
    SqlCommand command = qry.get(0).getCommand();
    assertTrue(command.isBatchedReWriteCompatible(), "update set name='default' is compatible with insert rewrite");
  }

  @Test
  void insertMultiInsert() throws SQLException {
    String query =
        "insert into test(id, name) values (:id,:name),(:id,:name) ON CONFLICT (id) DO NOTHING";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, PlaceholderStyle.NONE);
    SqlCommand command = qry.get(0).getCommand();
    assertEquals(34, command.getBatchRewriteValuesBraceOpenPosition());
    assertEquals(56, command.getBatchRewriteValuesBraceClosePosition());
  }

  @Test
  void valuesTableParse() throws SQLException {
    String query = "insert into values_table (id, name) values (?,?)";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, PlaceholderStyle.NONE);
    SqlCommand command = qry.get(0).getCommand();
    assertEquals(43, command.getBatchRewriteValuesBraceOpenPosition());
    assertEquals(49, command.getBatchRewriteValuesBraceClosePosition());

    query = "insert into table_values (id, name) values (?,?)";
    qry = Parser.parseJdbcSql(query, true, true, true, true, true, PlaceholderStyle.NONE);
    command = qry.get(0).getCommand();
    assertEquals(43, command.getBatchRewriteValuesBraceOpenPosition());
    assertEquals(49, command.getBatchRewriteValuesBraceClosePosition());
  }

  @Test
  void createTableParseWithOnDeleteClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "create table \"testTable\" (\"id\" INT SERIAL NOT NULL PRIMARY KEY, \"foreignId\" INT REFERENCES \"otherTable\" (\"id\") ON DELETE NO ACTION)";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, PlaceholderStyle.NONE, returningColumns);
    SqlCommand command = qry.get(0).getCommand();
    assertFalse(command.isReturningKeywordPresent(), "No returning keyword should be present");
    assertEquals(SqlCommandType.CREATE, command.getType());
  }

  @Test
  void createTableParseWithOnUpdateClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "create table \"testTable\" (\"id\" INT SERIAL NOT NULL PRIMARY KEY, \"foreignId\" INT REFERENCES \"otherTable\" (\"id\")) ON UPDATE NO ACTION";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, PlaceholderStyle.NONE, returningColumns);
    SqlCommand command = qry.get(0).getCommand();
    assertFalse(command.isReturningKeywordPresent(), "No returning keyword should be present");
    assertEquals(SqlCommandType.CREATE, command.getType());
  }

  @Test
  void alterTableParseWithOnDeleteClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "alter table \"testTable\" ADD \"foreignId\" INT REFERENCES \"otherTable\" (\"id\") ON DELETE NO ACTION";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, PlaceholderStyle.NONE, returningColumns);
    SqlCommand command = qry.get(0).getCommand();
    assertFalse(command.isReturningKeywordPresent(), "No returning keyword should be present");
    assertEquals(SqlCommandType.ALTER, command.getType());
  }

  @Test
  void alterTableParseWithOnUpdateClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "alter table \"testTable\" ADD \"foreignId\" INT REFERENCES \"otherTable\" (\"id\") ON UPDATE RESTRICT";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, PlaceholderStyle.NONE, returningColumns);
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
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, PlaceholderStyle.ANY, returningColumns);
    assertNotNull(qry);
    assertEquals(1, qry.size(), "There should only be one query returned here");
  }
}
