/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;

import org.junit.Assert;
import org.junit.Test;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

/**
 * Executes the query with the outer join syntax. The joined tables are encapsulated with parenthesis.
 * <b>Note:</b> This queries worked up to driver version 9.4.1211 (postgresql-9.4.1211.jre7.jar).
 * Encapsulation with parenthesis is used by third party like CrystalReports.
 */
public class OuterJoinSyntaxTest extends BaseTest4 {

  @Test
  public void testOuterJoinSyntaxWithSingleJoinAndWithoutOj() throws Exception {
    testOuterJoinSyntax(
        "select t1.id as t1_id, t1.text as t1_text,"
        + " t2.id as t2_id, t2.text as t2_text"
        + " from (values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (3, 'b')) as t2 (id, text) on (t1.id = t2.id)",
        Arrays.asList("1,one,1,a", "2,two,null,null"));
  }

  @Test
  public void testOuterJoinSyntaxWithMultipleJoinsAndWithoutOj() throws Exception {
    testOuterJoinSyntax(
        "select t1.id as t1_id, t1.text as t1_text,"
        + " t2.id as t2_id, t2.text as t2_text,"
        + " t3.id as t3_id, t3.text as t3_text"
        + " from (values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (3, 'b')) as t2 (id, text) on (t1.id = t2.id)"
        + " left outer join (values (4, '1'), (2, '2')) as t3 (id, text) on (t2.id = t3.id)",
        Arrays.asList("1,one,1,a,null,null", "2,two,null,null,null,null"));
  }

  @Test
  public void testOuterJoinSyntaxWithSingleJoinAndWithOj() throws Exception {
    testOuterJoinSyntax(
        "select t1.id as t1_id, t1.text as t1_text,"
        + " t2.id as t2_id, t2.text as t2_text"
        + " from {oj (values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (3, 'b')) as t2 (id, text) on (t1.id = t2.id) }",
        Arrays.asList("1,one,1,a", "2,two,null,null"));
  }

  @Test
  public void testOuterJoinSyntaxWithMultipleJoinsAndWithOj() throws Exception {
    testOuterJoinSyntax(
        "select t1.id as t1_id, t1.text as t1_text,"
        + " t2.id as t2_id, t2.text as t2_text,"
        + " t3.id as t3_id, t3.text as t3_text"
        + " from {oj (values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (3, 'b')) as t2 (id, text) on (t1.id = t2.id)"
        + " left outer join (values (1, '1'), (2, '2')) as t3 (id, text) on (t2.id = t3.id)}",
        Arrays.asList("1,one,1,a,1,1", "2,two,null,null,null,null"));
  }

  @Test
  public void testOuterJoinSyntaxWithMultipleJoinsAndWithOj2() throws Exception {
    // multiple joins with oj and missing space character after oj
    testOuterJoinSyntax(
        "select t1.id as t1_id, t1.text as t1_text,"
        + " t2.id as t2_id, t2.text as t2_text,"
        + " t3.id as t3_id, t3.text as t3_text"
        + " from {oj(values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (3, 'b')) as t2 (id, text) on (t1.id = t2.id)"
        + " left outer join (values (4, '1'), (2, '2')) as t3 (id, text) on (t2.id = t3.id)}",
        Arrays.asList("1,one,1,a,null,null", "2,two,null,null,null,null"));
  }

  @Test
  public void testOuterJoinSyntaxWithMultipleJoinsAndWithOj3() throws Exception {
    // multiple joins with oj and missing space character after oj and some more parenthesis
    testOuterJoinSyntax(
        "select t1.id as t1_id, t1.text as t1_text,"
        + " t2.id as t2_id, t2.text as t2_text,"
        + " t3.id as t3_id, t3.text as t3_text"
        + " from {oj(((values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (3, 'b')) as t2 (id, text) on (t1.id = t2.id))"
        + " left outer join (values (1, '1'), (4, '2')) as t3 (id, text) on (t2.id = t3.id))}",
        Arrays.asList("1,one,1,a,1,1", "2,two,null,null,null,null"));
  }

  /**
   * Executes the statement.
   *
   * @param theQuery the query to execute
   * @param expectedResult the expected columns in result set
   * @throws Exception on error
   */
  private void testOuterJoinSyntax(String theQuery, List<String> expectedResult) throws Exception {
    final Statement st = con.createStatement();
    try {
      final ResultSet rs = st.executeQuery(theQuery);
      try {
        Assert.assertEquals("SQL " + theQuery, TestUtil.join(TestUtil.resultSetToLines(rs)), TestUtil.join(expectedResult));
      } finally {
        TestUtil.closeQuietly(rs);
      }
    } finally {
      TestUtil.closeQuietly(st);
    }
  }

}
