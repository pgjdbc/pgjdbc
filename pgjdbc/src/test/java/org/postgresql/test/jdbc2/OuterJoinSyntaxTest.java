/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import java.util.Arrays;
import java.util.List;

/**
 * Executes the query with the outer join syntax. The joined tables are encapsulated with parenthesis.
 * <b>Note:</b> This queries worked up to driver version 9.4.1211 (postgresql-9.4.1211.jre7.jar).
 * Encapsulation with parenthesis is used by third party like CrystalReports.
 */
public class OuterJoinSyntaxTest {

  /**
   * The connection to the test database.
   */
  private Connection connection;

  /**
   * Prepares the test environment.
   *
   * @throws Exception on error
   */
  @Before
  public void setUp() throws Exception {
    connection = TestUtil.openDB();
  }

  /**
   * Closes resources.
   *
   * @throws Exception on error
   */
  @After
  public void tearDown() throws Exception {
    TestUtil.closeDB(connection);
  }

  @Test
  public void testOuterJoinSyntaxWithSingleJoinAndWithoutOj() throws Exception {
    testOuterJoinSyntax(
        "select t1.id as t1_id, t1.text as t1_text,"
        + " t2.id as t2_id, t2.text as t2_text"
        + " from (values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (2, 'b')) as t2 (id, text) on (t1.id = t2.id)",
        Arrays.asList("t1_id", "t1_text", "t2_id", "t2_text"));
  }

  @Test
  public void testOuterJoinSyntaxWithMultipleJoinsAndWithoutOj() throws Exception {
    testOuterJoinSyntax(
        "select t1.id as t1_id, t1.text as t1_text,"
        + " t2.id as t2_id, t2.text as t2_text,"
        + " t3.id as t3_id, t3.text as t3_text"
        + " from (values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (2, 'b')) as t2 (id, text) on (t1.id = t2.id)"
        + " left outer join (values (1, '1'), (2, '2')) as t3 (id, text) on (t2.id = t3.id)",
        Arrays.asList("t1_id", "t1_text", "t2_id", "t2_text", "t3_id", "t3_text"));
  }

  @Test
  public void testOuterJoinSyntaxWithSingleJoinAndWithOj() throws Exception {
    testOuterJoinSyntax(
        "select t1.id as t1_id, t1.text as t1_text,"
        + " t2.id as t2_id, t2.text as t2_text"
        + " from {oj (values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (2, 'b')) as t2 (id, text) on (t1.id = t2.id) }",
        Arrays.asList("t1_id", "t1_text", "t2_id", "t2_text"));
  }

  @Test
  public void testOuterJoinSyntaxWithMultipleJoinsAndWithOj() throws Exception {
    testOuterJoinSyntax(
        "select t1.id as t1_id, t1.text as t1_text,"
        + " t2.id as t2_id, t2.text as t2_text,"
        + " t3.id as t3_id, t3.text as t3_text"
        + " from {oj (values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (2, 'b')) as t2 (id, text) on (t1.id = t2.id)"
        + " left outer join (values (1, '1'), (2, '2')) as t3 (id, text) on (t2.id = t3.id)}",
        Arrays.asList("t1_id", "t1_text", "t2_id", "t2_text", "t3_id", "t3_text"));
  }

  @Test
  public void testOuterJoinSyntaxWithMultipleJoinsAndWithOj2() throws Exception {
    // multiple joins with oj and missing space character after oj
    testOuterJoinSyntax(
        "select t1.id as t1_id, t1.text as t1_text,"
        + " t2.id as t2_id, t2.text as t2_text,"
        + " t3.id as t3_id, t3.text as t3_text"
        + " from {oj(values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (2, 'b')) as t2 (id, text) on (t1.id = t2.id)"
        + " left outer join (values (1, '1'), (2, '2')) as t3 (id, text) on (t2.id = t3.id)}",
        Arrays.asList("t1_id", "t1_text", "t2_id", "t2_text", "t3_id", "t3_text"));
  }

  @Test
  public void testOuterJoinSyntaxWithMultipleJoinsAndWithOj3() throws Exception {
    // multiple joins with oj and missing space character after oj and some more parenthesis
    testOuterJoinSyntax(
        "select t1.id as t1_id, t1.text as t1_text,"
        + " t2.id as t2_id, t2.text as t2_text,"
        + " t3.id as t3_id, t3.text as t3_text"
        + " from {oj(((values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (2, 'b')) as t2 (id, text) on (t1.id = t2.id))"
        + " left outer join (values (1, '1'), (2, '2')) as t3 (id, text) on (t2.id = t3.id))}",
        Arrays.asList("t1_id", "t1_text", "t2_id", "t2_text", "t3_id", "t3_text"));
  }

  /**
   * Executes the statment.
   *
   * @param theQuery the query to execute
   * @param theExpectedColumns the expected columns in result set
   * @throws Exception on error
   */
  private void testOuterJoinSyntax(final String theQuery, List<String> theExpectedColumns) throws Exception {
    final Statement _st = connection.createStatement();

    try {
      final ResultSet _rs = _st.executeQuery(theQuery);

      try {
        final ResultSetMetaData _md = _rs.getMetaData();
        Assert.assertEquals(theExpectedColumns.size(), _md.getColumnCount());
        for (int _i = 0; _i < _md.getColumnCount(); _i++) {
          Assert.assertTrue(theExpectedColumns.contains(_md.getColumnLabel(_i + 1)));
        }

        int _count = 0;
        while (_rs.next()) {
          for (final String _label : theExpectedColumns) {
            _rs.getObject(_label); // just try to get the values without check
          }
          _count++;
        }
        Assert.assertEquals(_count, 2);
      } finally {
        _rs.close();
      }
    } finally {
      _st.close();
    }
  }

}
