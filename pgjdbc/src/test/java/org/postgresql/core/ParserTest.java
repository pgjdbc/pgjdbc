/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.jdbc.EscapeSyntaxCallMode;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.sql.SQLException;
import java.util.List;

/**
 * Test cases for the Parser.
 * @author Jeremy Whiting jwhiting@redhat.com
 */
public class ParserTest {

  /**
   * Test to make sure delete command is detected by parser and detected via
   * api. Mix up the case of the command to check detection continues to work.
   */
  @Test
  public void testDeleteCommandParsing() {
    char[] command = new char[6];
    "DELETE".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse upper case command.", Parser.parseDeleteKeyword(command, 0));
    "DelEtE".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseDeleteKeyword(command, 0));
    "deleteE".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseDeleteKeyword(command, 0));
    "delete".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse lower case command.", Parser.parseDeleteKeyword(command, 0));
    "Delete".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseDeleteKeyword(command, 0));
  }

  /**
   * Test UPDATE command parsing.
   */
  @Test
  public void testUpdateCommandParsing() {
    char[] command = new char[6];
    "UPDATE".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse upper case command.", Parser.parseUpdateKeyword(command, 0));
    "UpDateE".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseUpdateKeyword(command, 0));
    "updatE".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseUpdateKeyword(command, 0));
    "Update".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseUpdateKeyword(command, 0));
    "update".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse lower case command.", Parser.parseUpdateKeyword(command, 0));
  }

  /**
   * Test MOVE command parsing.
   */
  @Test
  public void testMoveCommandParsing() {
    char[] command = new char[4];
    "MOVE".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse upper case command.", Parser.parseMoveKeyword(command, 0));
    "mOVe".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseMoveKeyword(command, 0));
    "movE".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseMoveKeyword(command, 0));
    "Move".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseMoveKeyword(command, 0));
    "move".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse lower case command.", Parser.parseMoveKeyword(command, 0));
  }

  /**
   * Test WITH command parsing.
   */
  @Test
  public void testWithCommandParsing() {
    char[] command = new char[4];
    "WITH".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse upper case command.", Parser.parseWithKeyword(command, 0));
    "wITh".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseWithKeyword(command, 0));
    "witH".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseWithKeyword(command, 0));
    "With".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseWithKeyword(command, 0));
    "with".getChars(0, 4, command, 0);
    assertTrue("Failed to correctly parse lower case command.", Parser.parseWithKeyword(command, 0));
  }

  /**
   * Test SELECT command parsing.
   */
  @Test
  public void testSelectCommandParsing() {
    char[] command = new char[6];
    "SELECT".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse upper case command.", Parser.parseSelectKeyword(command, 0));
    "sELect".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseSelectKeyword(command, 0));
    "selecT".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseSelectKeyword(command, 0));
    "Select".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse mixed case command.", Parser.parseSelectKeyword(command, 0));
    "select".getChars(0, 6, command, 0);
    assertTrue("Failed to correctly parse lower case command.", Parser.parseSelectKeyword(command, 0));
  }

  @Test
  public void testEscapeProcessing() throws Exception {
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
  public void testModifyJdbcCall() throws SQLException {
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
  public void testUnterminatedEscape() throws Exception {
    assertEquals("{oj ", Parser.replaceProcessing("{oj ", true, false));
  }

  @Test
  @Ignore(value = "returning in the select clause is hard to distinguish from insert ... returning *")
  public void insertSelectFakeReturning() throws SQLException {
    String query =
        "insert test(id, name) select 1, 'value' as RETURNING from test2";
    List<NativeQuery> qry =
        Parser.parseJdbcSql(
            query, true, true, true, true, true);
    boolean returningKeywordPresent = qry.get(0).command.isReturningKeywordPresent();
    Assert.assertFalse("Query does not have returning clause " + query, returningKeywordPresent);
  }

  @Test
  public void insertSelectReturning() throws SQLException {
    String query =
        "insert test(id, name) select 1, 'value' from test2 RETURNING id";
    List<NativeQuery> qry =
        Parser.parseJdbcSql(
            query, true, true, true, true, true);
    boolean returningKeywordPresent = qry.get(0).command.isReturningKeywordPresent();
    Assert.assertTrue("Query has a returning clause " + query, returningKeywordPresent);
  }

  @Test
  public void insertReturningInWith() throws SQLException {
    String query =
        "with x as (insert into mytab(x) values(1) returning x) insert test(id, name) select 1, 'value' from test2";
    List<NativeQuery> qry =
        Parser.parseJdbcSql(
            query, true, true, true, true, true);
    boolean returningKeywordPresent = qry.get(0).command.isReturningKeywordPresent();
    Assert.assertFalse("There's no top-level <<returning>> clause " + query, returningKeywordPresent);
  }

  @Test
  public void insertBatchedReWriteOnConflict() throws SQLException {
    String query = "insert into test(id, name) values (:id,:name) ON CONFLICT (id) DO NOTHING";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertEquals(34, command.getBatchRewriteValuesBraceOpenPosition());
    Assert.assertEquals(44, command.getBatchRewriteValuesBraceClosePosition());
  }

  @Test
  public void insertBatchedReWriteOnConflictUpdateBind() throws SQLException {
    String query = "insert into test(id, name) values (?,?) ON CONFLICT (id) UPDATE SET name=?";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertFalse("update set name=? is NOT compatible with insert rewrite", command.isBatchedReWriteCompatible());
  }

  @Test
  public void insertBatchedReWriteOnConflictUpdateConstant() throws SQLException {
    String query = "insert into test(id, name) values (?,?) ON CONFLICT (id) UPDATE SET name='default'";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertTrue("update set name='default' is compatible with insert rewrite", command.isBatchedReWriteCompatible());
  }

  @Test
  public void insertMultiInsert() throws SQLException {
    String query =
        "insert into test(id, name) values (:id,:name),(:id,:name) ON CONFLICT (id) DO NOTHING";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertEquals(34, command.getBatchRewriteValuesBraceOpenPosition());
    Assert.assertEquals(56, command.getBatchRewriteValuesBraceClosePosition());
  }

  @Test
  public void valuesTableParse() throws SQLException {
    String query = "insert into values_table (id, name) values (?,?)";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertEquals(43,command.getBatchRewriteValuesBraceOpenPosition());
    Assert.assertEquals(49,command.getBatchRewriteValuesBraceClosePosition());

    query = "insert into table_values (id, name) values (?,?)";
    qry = Parser.parseJdbcSql(query, true, true, true, true, true);
    command = qry.get(0).getCommand();
    Assert.assertEquals(43,command.getBatchRewriteValuesBraceOpenPosition());
    Assert.assertEquals(49,command.getBatchRewriteValuesBraceClosePosition());
  }

  @Test
  public void createTableParseWithOnDeleteClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "create table \"testTable\" (\"id\" INT SERIAL NOT NULL PRIMARY KEY, \"foreignId\" INT REFERENCES \"otherTable\" (\"id\") ON DELETE NO ACTION)";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, returningColumns);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertFalse("No returning keyword should be present", command.isReturningKeywordPresent());
    Assert.assertEquals(SqlCommandType.CREATE, command.getType());
  }

  @Test
  public void createTableParseWithOnUpdateClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "create table \"testTable\" (\"id\" INT SERIAL NOT NULL PRIMARY KEY, \"foreignId\" INT REFERENCES \"otherTable\" (\"id\")) ON UPDATE NO ACTION";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, returningColumns);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertFalse("No returning keyword should be present", command.isReturningKeywordPresent());
    Assert.assertEquals(SqlCommandType.CREATE, command.getType());
  }

  @Test
  public void alterTableParseWithOnDeleteClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "alter table \"testTable\" ADD \"foreignId\" INT REFERENCES \"otherTable\" (\"id\") ON DELETE NO ACTION";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, returningColumns);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertFalse("No returning keyword should be present", command.isReturningKeywordPresent());
    Assert.assertEquals(SqlCommandType.ALTER, command.getType());
  }

  @Test
  public void alterTableParseWithOnUpdateClause() throws SQLException {
    String[] returningColumns = {"*"};
    String query = "alter table \"testTable\" ADD \"foreignId\" INT REFERENCES \"otherTable\" (\"id\") ON UPDATE RESTRICT";
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true, returningColumns);
    SqlCommand command = qry.get(0).getCommand();
    Assert.assertFalse("No returning keyword should be present", command.isReturningKeywordPresent());
    Assert.assertEquals(SqlCommandType.ALTER, command.getType());
  }
}
