/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2003-2016, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.core;

import junit.framework.TestCase;

import org.junit.Assert;

/**
 * Test cases for the Parser.
 * @author Jeremy Whiting jwhiting@redhat.com
 */
public class ParserTest extends TestCase {

  /**
   * Test to make sure delete command is detected by parser and detected via
   * api. Mix up the case of the command to check detection continues to work.
   */
  public void testDeleteCommandParsing() {
    char[] command = new char[6];
    "DELETE".getChars(0, 6, command, 0);
    Assert.assertTrue("Failed to correctly parse upper case command.", Parser.parseDeleteKeyword(command, 0));
    "DelEtE".getChars(0, 6, command, 0);
    Assert.assertTrue("Failed to correctly parse mixed case command.", Parser.parseDeleteKeyword(command, 0));
    "deleteE".getChars(0, 6, command, 0);
    Assert.assertTrue("Failed to correctly parse mixed case command.", Parser.parseDeleteKeyword(command, 0));
    "delete".getChars(0, 6, command, 0);
    Assert.assertTrue("Failed to correctly parse lower case command.", Parser.parseDeleteKeyword(command, 0));
    "Delete".getChars(0, 6, command, 0);
    Assert.assertTrue("Failed to correctly parse mixed case command.", Parser.parseDeleteKeyword(command, 0));
  }

  /**
   * Test UPDATE command parsing.
   */
  public void testUpdateCommandParsing() {
    char[] command = new char[6];
    "UPDATE".getChars(0, 6, command, 0);
    Assert.assertTrue("Failed to correctly parse upper case command.", Parser.parseUpdateKeyword(command, 0));
    "UpDateE".getChars(0, 6, command, 0);
    Assert.assertTrue("Failed to correctly parse mixed case command.", Parser.parseUpdateKeyword(command, 0));
    "updatE".getChars(0, 6, command, 0);
    Assert.assertTrue("Failed to correctly parse mixed case command.", Parser.parseUpdateKeyword(command, 0));
    "Update".getChars(0, 6, command, 0);
    Assert.assertTrue("Failed to correctly parse mixed case command.", Parser.parseUpdateKeyword(command, 0));
    "update".getChars(0, 6, command, 0);
    Assert.assertTrue("Failed to correctly parse lower case command.", Parser.parseUpdateKeyword(command, 0));
  }

  /**
   * Test MOVE command parsing.
   */
  public void testMoveCommandParsing() {
    char[] command = new char[4];
    "MOVE".getChars(0, 4, command, 0);
    Assert.assertTrue("Failed to correctly parse upper case command.", Parser.parseMoveKeyword(command, 0));
    "mOVe".getChars(0, 4, command, 0);
    Assert.assertTrue("Failed to correctly parse mixed case command.", Parser.parseMoveKeyword(command, 0));
    "movE".getChars(0, 4, command, 0);
    Assert.assertTrue("Failed to correctly parse mixed case command.", Parser.parseMoveKeyword(command, 0));
    "Move".getChars(0, 4, command, 0);
    Assert.assertTrue("Failed to correctly parse mixed case command.", Parser.parseMoveKeyword(command, 0));
    "move".getChars(0, 4, command, 0);
    Assert.assertTrue("Failed to correctly parse lower case command.", Parser.parseMoveKeyword(command, 0));
  }

  /**
   * Test WITH command parsing.
   */
  public void testWithCommandParsing() {
    char[] command = new char[4];
    "WITH".getChars(0, 4, command, 0);
    Assert.assertTrue("Failed to correctly parse upper case command.", Parser.parseWithKeyword(command, 0));
    "wITh".getChars(0, 4, command, 0);
    Assert.assertTrue("Failed to correctly parse mixed case command.", Parser.parseWithKeyword(command, 0));
    "witH".getChars(0, 4, command, 0);
    Assert.assertTrue("Failed to correctly parse mixed case command.", Parser.parseWithKeyword(command, 0));
    "With".getChars(0, 4, command, 0);
    Assert.assertTrue("Failed to correctly parse mixed case command.", Parser.parseWithKeyword(command, 0));
    "with".getChars(0, 4, command, 0);
    Assert.assertTrue("Failed to correctly parse lower case command.", Parser.parseWithKeyword(command, 0));
  }

  /**
   * Test SELECT command parsing.
   */
  public void testSelectCommandParsing() {
    char[] command = new char[6];
    "SELECT".getChars(0, 6, command, 0);
    Assert.assertTrue("Failed to correctly parse upper case command.", Parser.parseSelectKeyword(command, 0));
    "sELect".getChars(0, 6, command, 0);
    Assert.assertTrue("Failed to correctly parse mixed case command.", Parser.parseSelectKeyword(command, 0));
    "selecT".getChars(0, 6, command, 0);
    Assert.assertTrue("Failed to correctly parse mixed case command.", Parser.parseSelectKeyword(command, 0));
    "Select".getChars(0, 6, command, 0);
    Assert.assertTrue("Failed to correctly parse mixed case command.", Parser.parseSelectKeyword(command, 0));
    "select".getChars(0, 6, command, 0);
    Assert.assertTrue("Failed to correctly parse lower case command.", Parser.parseSelectKeyword(command, 0));
  }

  public void testEscapeProcessing() throws Exception {
    Assert.assertEquals("DATE '1999-01-09'", Parser.replaceProcessing("{d '1999-01-09'}", true, false));
    Assert.assertEquals("DATE '1999-01-09'", Parser.replaceProcessing("{D  '1999-01-09'}", true, false));
    Assert.assertEquals("TIME '20:00:03'", Parser.replaceProcessing("{t '20:00:03'}", true, false));
    Assert.assertEquals("TIME '20:00:03'", Parser.replaceProcessing("{T '20:00:03'}", true, false));
    Assert.assertEquals("TIMESTAMP '1999-01-09 20:11:11.123455'", Parser.replaceProcessing("{ts '1999-01-09 20:11:11.123455'}", true, false));
    Assert.assertEquals("TIMESTAMP '1999-01-09 20:11:11.123455'", Parser.replaceProcessing("{Ts '1999-01-09 20:11:11.123455'}", true, false));

    Assert.assertEquals("user", Parser.replaceProcessing("{fn user()}", true, false));
    Assert.assertEquals("cos(1)", Parser.replaceProcessing("{fn cos(1)}", true, false));
    Assert.assertEquals("extract(week from DATE '2005-01-24')", Parser.replaceProcessing("{fn week({d '2005-01-24'})}", true, false));

    Assert.assertEquals("\"T1\" LEFT OUTER JOIN t2 ON \"T1\".id = t2.id",
            Parser.replaceProcessing("{oj \"T1\" LEFT OUTER JOIN t2 ON \"T1\".id = t2.id}", true, false));

    Assert.assertEquals("ESCAPE '_'", Parser.replaceProcessing("{escape '_'}", true, false));

    // nothing should be changed in that case, no valid escape code
    Assert.assertEquals("{obj : 1}", Parser.replaceProcessing("{obj : 1}", true, false));
  }

  public void testUnterminatedEscape() throws Exception {
    Assert.assertEquals("{oj ", Parser.replaceProcessing("{oj ", true, false));
  }
}
