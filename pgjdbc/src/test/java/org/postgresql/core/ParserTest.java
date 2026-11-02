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
   * A call body that ends in a backslash inside a string constant used to step the scan past the
   * end of the input, which skipped the "ran out of query" check and reached
   * {@code jdbcSql.substring(startIndex, -1)}.
   */
  @Test
  void trailingBackslashInCallBodyIsASyntaxError() {
    for (String sql : new String[]{"{call f('a\\", "{?=call f('a\\", "{call f('\\"}) {
      PSQLException e = assertThrows(PSQLException.class,
          () -> Parser.modifyJdbcCall(sql, false, ServerVersion.v11.getVersionNum(),
              EscapeSyntaxCallMode.CALL),
          sql);
      assertEquals(PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL.getState(), e.getSQLState(), sql);
      // The same input with standard_conforming_strings on was already reported this way
      PSQLException stdStrings = assertThrows(PSQLException.class,
          () -> Parser.modifyJdbcCall(sql, true, ServerVersion.v11.getVersionNum(),
              EscapeSyntaxCallMode.CALL),
          sql);
      assertEquals(PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL.getState(),
          stdStrings.getSQLState(), sql);
    }
  }

  /**
   * Where the backslash does have something to escape, the escaped quote still does not end the
   * string constant, and the body reaches the server unchanged. This one passes on the unfixed
   * code as well: it is here to show the clamp did not disturb the escape path.
   */
  @Test
  void backslashEscapeInCallBodyIsHonoured() throws SQLException {
    assertEquals("call f('a\\'b')",
        Parser.modifyJdbcCall("{call f('a\\'b')}", false, ServerVersion.v11.getVersionNum(),
            EscapeSyntaxCallMode.CALL).getSql());
    // With standard_conforming_strings on the backslash is an ordinary character, so the quote
    // after it closes the constant and the driver never finds the closing brace. PostgreSQL
    // rejects the same text too, though it tokenizes it differently: it reads b' as the start of
    // a bit-string literal and reports that as unterminated.
    assertThrows(PSQLException.class,
        () -> Parser.modifyJdbcCall("{call f('a\\'b')}", true, ServerVersion.v11.getVersionNum(),
            EscapeSyntaxCallMode.CALL));
  }

  /**
   * A comment may stand anywhere between the opening brace and {@code call}, as it already could
   * before the opening brace and after the closing one. Such a comment used to be a syntax error.
   */
  @ParameterizedTest
  @MethodSource("commentBeforeTheCallKeyword")
  void aCommentBeforeTheCallKeywordIsSkipped(String sql, String expected) throws SQLException {
    assertEquals(expected, modifyCall(sql), sql);
  }

  static Stream<Arguments> commentBeforeTheCallKeyword() {
    return Stream.of(
        argumentSet("block comment before call", "{/*c*/call f(?)}", "call f(?)"),
        argumentSet("block comment before ?", "{/*c*/? = call f(?)}", "call f(?,?)"),
        argumentSet("block comment between ? and =", "{? /*c*/ = call f(?)}", "call f(?,?)"),
        argumentSet("block comment between = and call", "{? = /*c*/ call f(?)}", "call f(?,?)"),
        argumentSet("line comment before ?", "{-- c\n? = call f(?)}", "call f(?,?)"),
        argumentSet("line comment between = and call", "{? = -- c\n call f(?)}", "call f(?,?)")
    );
  }

  /**
   * Skipping a comment does not admit a word other than {@code call} after it.
   */
  @ParameterizedTest
  @MethodSource("commentBeforeAWordOtherThanCall")
  void aCommentBeforeAWordOtherThanCallIsRefused(String sql) {
    PSQLException e = assertThrows(PSQLException.class, () -> modifyCall(sql), sql);
    assertEquals(PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL.getState(), e.getSQLState(), sql);
  }

  static Stream<Arguments> commentBeforeAWordOtherThanCall() {
    return Stream.of(
        argumentSet("after {", "{/*c*/ foo(?)}"),
        argumentSet("after ?", "{? /*c*/ foo(?)}"),
        argumentSet("after ? =", "{? = /*c*/ foo(?)}")
    );
  }

  /**
   * PostgreSQL reads a string constant, a delimited identifier, a dollar-quoted string and a block
   * comment as one token, so a brace, a quote or a {@code ;} inside one belongs to the token and
   * neither ends nor breaks the call, and neither does a {@code $$}, a {@code /*} or a {@code --}
   * inside a string constant. Only the string constant was skipped before, and an {@code E''}
   * string ended at its first escaped quote; a brace in any of the others ended the call, and an
   * apostrophe in a block comment opened a string.
   */
  @ParameterizedTest
  @MethodSource("tokenHoldingABraceOrAQuote")
  void aTokenInTheBodyIsSkippedWhole(String sql, String expected) throws SQLException {
    assertEquals(expected, modifyCall(sql), sql);
  }

  static Stream<Arguments> tokenHoldingABraceOrAQuote() {
    return Stream.of(
        argumentSet("} in a string constant", "{call f('a}b')}", "call f('a}b')"),
        argumentSet("; in a string constant", "{call f(';')}", "call f(';')"),
        argumentSet("} after an escaped quote in an E'' string",
            "{call f(E'\\'}')}", "call f(E'\\'}')"),
        argumentSet("$$ after an escaped quote in an E'' string",
            "{call f(E'\\'$$', E'\\'$$')}", "call f(E'\\'$$', E'\\'$$')"),
        argumentSet("\" in a string constant", "{call f('\"}', '\"')}", "call f('\"}', '\"')"),
        argumentSet("$$ in a string constant", "{call f('$$}', '$$')}", "call f('$$}', '$$')"),
        argumentSet("/* in a string constant", "{call f('/*}', '*/')}", "call f('/*}', '*/')"),
        argumentSet("-- in a string constant", "{call f('-- }', ?)}", "call f('-- }', ?)"),
        argumentSet("} in a delimited identifier", "{call \"we}ird\"()}", "call \"we}ird\"()"),
        argumentSet("{ in a delimited identifier", "{call \"we{ird\"()}", "call \"we{ird\"()"),
        argumentSet("} after a doubled quote in a delimited identifier",
            "{call \"a\"\"}\"()}", "call \"a\"\"}\"()"),
        argumentSet("} in a dollar-quoted string", "{call f($$a}b$$)}", "call f($$a}b$$)"),
        argumentSet("} in a tagged dollar-quoted string", "{call f($t$a}b$t$)}", "call f($t$a}b$t$)"),
        argumentSet("; in a dollar-quoted string", "{call f($$;$$)}", "call f($$;$$)"),
        argumentSet("} in a block comment", "{call foo() /*}*/ }", "call foo() /*}*/ "),
        argumentSet("} in a nested block comment",
            "{call f(?) /* /* } */ */}", "call f(?) /* /* } */ */"),
        argumentSet("' in a block comment", "{call f(?) /* don't */}", "call f(?) /* don't */"),
        argumentSet("; in a block comment", "{call f(?) /* ; */}", "call f(?) /* ; */"),
        argumentSet("$ inside an identifier starts no dollar quote",
            "{call my$fn$x('}')}", "call my$fn$x('}')"),
        argumentSet("a parameter number starts no dollar quote", "{call f($1, '}')}", "call f($1, '}')"),
        argumentSet("a division starts no block comment", "{call f(4/2, '}')}", "call f(4/2, '}')")
    );
  }

  /**
   * A call body that is not one complete call is refused with SQLSTATE 2F003 rather than sent: an
   * unterminated token, a nested escape left open, a {@code ;} outside any token, or no closing
   * brace at all. An
   * unterminated token and an open nested escape used to be accepted. Through
   * {@code Connection.prepareCall}, escape processing refuses an unterminated token before this
   * parser runs.
   */
  @ParameterizedTest
  @MethodSource("malformedCallBody")
  void aMalformedCallBodyIsRefused(String sql) {
    PSQLException e = assertThrows(PSQLException.class, () -> modifyCall(sql), sql);
    assertEquals(PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL.getState(), e.getSQLState(), sql);
  }

  static Stream<Arguments> malformedCallBody() {
    return Stream.of(
        argumentSet("unterminated delimited identifier", "{call f(\"x)}"),
        argumentSet("unterminated dollar-quoted string", "{call f($$x)}"),
        argumentSet("tagged dollar quote closed by another tag", "{call f($t$x$$)}"),
        argumentSet("unterminated block comment", "{call f(/*x)}"),
        argumentSet("block comment closed at one level of two", "{call f(/* /* */)}"),
        argumentSet("nested escape left open", "{call f({fn a()}"),
        argumentSet("opening brace left open", "{call f({)}"),
        argumentSet("; outside any token", "{call f(?); select 1}"),
        argumentSet("line comment and no brace", "{call f(?) --")
    );
  }

  /**
   * The body of a call may hold further JDBC escapes, nested to any depth. The scan used to flip one
   * flag on every brace and did not step past the closing brace of an inner escape, so that brace
   * ended the call. Escape processing expands the escapes it knows, such as {@code {d ...}} and
   * {@code {fn ...}}, before a {@code CallableStatement} reaches this parser, so those arrive here
   * only from a direct caller.
   */
  @ParameterizedTest
  @MethodSource("nestedEscape")
  void aNestedEscapeIsKeptInTheBody(String sql, String expected) throws SQLException {
    assertEquals(expected, modifyCall(sql), sql);
  }

  static Stream<Arguments> nestedEscape() {
    return Stream.of(
        argumentSet("{d} escape", "{call f({d '2020-01-01'})}", "call f({d '2020-01-01'})"),
        argumentSet("two escapes side by side",
            "{call f({fn a()}, {fn b()})}", "call f({fn a()}, {fn b()})"),
        argumentSet("escape two levels deep",
            "{call f({fn abs({fn abs(?)})})}", "call f({fn abs({fn abs(?)})})"),
        argumentSet("escape after ? =", "{? = call f({d '2020-01-01'})}", "call f(?,{d '2020-01-01'})")
    );
  }

  /**
   * A line comment in the body is text: a quote, a {@code ;}, a {@code $}, a {@code /*} or an
   * opening brace inside it starts nothing. That holds whether a newline ends the comment or the
   * comment runs into the closing brace.
   */
  @ParameterizedTest
  @MethodSource("lineCommentHoldingASpecialCharacter")
  void aLineCommentInTheBodyIsText(String sql, String expected) throws SQLException {
    assertEquals(expected, modifyCall(sql), sql);
  }

  static Stream<Arguments> lineCommentHoldingASpecialCharacter() {
    return Stream.of(
        argumentSet("apostrophe", "{call f(?) -- don't\n}", "call f(?) -- don't\n"),
        argumentSet("apostrophe, then the closing brace", "{call f(?) -- don't}", "call f(?) -- don't"),
        argumentSet("semicolon", "{call f(?) -- a; b\n}", "call f(?) -- a; b\n"),
        argumentSet("semicolon, then the closing brace", "{call f(?) -- a; b}", "call f(?) -- a; b"),
        argumentSet("double quote", "{call f(?) -- 6\" pipe\n}", "call f(?) -- 6\" pipe\n"),
        argumentSet("dollar quote", "{call f(?) -- costs $$\n}", "call f(?) -- costs $$\n"),
        argumentSet("block comment opener", "{call f(?) -- see /*\n}", "call f(?) -- see /*\n"),
        argumentSet("opening brace", "{call f(?) -- {\n}", "call f(?) -- {\n"),
        argumentSet("opening braces, then the closing brace", "{call f(?) -- {{}", "call f(?) -- {{")
    );
  }

  /**
   * A closing brace inside a line comment is comment text when a later brace closes the call.
   * Otherwise the last brace inside the comment ends the call: a comment with no newline after it
   * runs to the end of the input, so in {@code {call f(?) -- x}} the brace has to be the
   * terminator. Where both readings parse, the brace is text.
   */
  @ParameterizedTest
  @MethodSource("braceInALineComment")
  void aBraceInALineCommentEndsTheCallOnlyWhenNoLaterBraceDoes(String sql, String expected)
      throws SQLException {
    assertEquals(expected, modifyCall(sql), sql);
  }

  static Stream<Arguments> braceInALineComment() {
    return Stream.of(
        argumentSet("text: a later brace closes the call",
            "{call f(?) -- the } case\n}", "call f(?) -- the } case\n"),
        argumentSet("text: a carriage return ends the comment",
            "{call f(?) -- the } case\r}", "call f(?) -- the } case\r"),
        argumentSet("text: the argument list goes on after the comment",
            "{call f(?, -- }\n ?)}", "call f(?, -- }\n ?)"),
        argumentSet("text: both readings parse",
            "{call f(?) -- a}/*\n} --*/", "call f(?) -- a}/*\n"),
        argumentSet("end: nothing follows", "{call f(?) -- x}", "call f(?) -- x"),
        argumentSet("end: the last of two braces", "{call f(?) -- a} b}", "call f(?) -- a} b"),
        argumentSet("end: after a quoted brace", "{call f(?) -- '}' }", "call f(?) -- '}' "),
        argumentSet("end: after a brace in a quoted word",
            "{call f(?) -- see 'a}b'}", "call f(?) -- see 'a}b'"),
        argumentSet("end: the comment is empty", "{call f(?) --}", "call f(?) --"),
        argumentSet("end: ? = call", "{? = call f() -- x}", "call f(?) -- x"),
        argumentSet("end: a newline follows", "{call f(?) -- x}\n", "call f(?) -- x"),
        argumentSet("end: a CRLF follows", "{call f(?) -- x}\r\n", "call f(?) -- x"),
        argumentSet("end: a line comment holding } follows",
            "{call f(?) -- x}\n -- }", "call f(?) -- x"),
        argumentSet("end: a block comment holding } follows",
            "{call f(?) -- x}\n /* } */", "call f(?) -- x"),
        argumentSet("end: an earlier line comment ends at its newline",
            "{call f(?) -- a\n -- b}\n", "call f(?) -- a\n -- b"),
        argumentSet("a lone minus starts no comment", "{call f(1-'}')}", "call f(1-'}')")
    );
  }

  /**
   * {@code Connection.prepareCall} runs escape processing before the call parser, and the calls
   * this parser now accepts come through both steps intact.
   */
  @ParameterizedTest
  @MethodSource("callThroughEscapeProcessing")
  void aCallPassesThroughEscapeProcessingIntact(String sql, String expected) throws SQLException {
    String processed = Parser.replaceProcessing(sql, true, true);
    assertEquals(expected, modifyCall(processed), sql);
  }

  static Stream<Arguments> callThroughEscapeProcessing() {
    return Stream.of(
        argumentSet("} in a delimited identifier", "{call \"we}ird\"()}", "call \"we}ird\"()"),
        argumentSet("} in a dollar-quoted string", "{call f($$a}b$$)}", "call f($$a}b$$)"),
        argumentSet("' in a block comment", "{call f(?) /* don't */}", "call f(?) /* don't */"),
        argumentSet("' in a line comment", "{call f(?) -- don't}", "call f(?) -- don't"),
        argumentSet("escaped quote in an E'' string", "{call f(E'it\\'s')}", "call f(E'it\\'s')"),
        argumentSet("block comment before ?", "{/*c*/? = call f(?)}", "call f(?,?)"),
        argumentSet("escape that escape processing does not know", "{call f({x})}", "call f({x})")
    );
  }

  private static String modifyCall(String sql) throws SQLException {
    return Parser.modifyJdbcCall(sql, true, ServerVersion.v11.getVersionNum(),
        EscapeSyntaxCallMode.CALL).getSql();
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
