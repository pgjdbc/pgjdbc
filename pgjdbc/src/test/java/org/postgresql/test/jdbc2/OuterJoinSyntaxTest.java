/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
package org.postgresql.test.jdbc2;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.postgresql.test.TestUtil;

/**
 * Executes the query with the outer join syntax. The joined tables are encapsulated with parenthesis.
 * <b>Note:</b> This queries worked up to driver version 9.4.1211 (postgresql-9.4.1211.jre7.jar).
 * Encapsulation with parenthesis is used by third party like CrystalReports.
 */
public class OuterJoinSyntaxTest
{
  
  /**
   * The connection to the test database.
   */
  private Connection connection;

  /**
   * Prepares the test environment.
   *
   * @throws Exception
   */
  @Before
  public void setUp()
      throws Exception
  {
    connection = TestUtil.openDB();
  }

  /**
   * Closes resources.
   *
   * @throws Exception
   */
  @After
  public void tearDown()
      throws Exception
  {
    TestUtil.closeDB(connection);
  }
  
  @Test
  public void testOuterJoinSyntaxWithSingleJoinAndWithoutOj()
      throws Exception
  {
    testOuterJoinSyntax(
        "select *"
        + " from (values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (2, 'b')) as t2 (id, text) on (t1.id = t2.id)");
  }
  
  @Test
  public void testOuterJoinSyntaxWithMultipleJoinsAndWithoutOj()
      throws Exception
  {
    testOuterJoinSyntax(
        "select *"
        + " from (values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (2, 'b')) as t2 (id, text) on (t1.id = t2.id)"
        + " left outer join (values (1, '1'), (2, '2')) as t3 (id, text) on (t2.id = t3.id)");
  }
  
  @Test
  public void testOuterJoinSyntaxWithSingleJoinAndWithOj()
      throws Exception
  {
    testOuterJoinSyntax(
        "select *"
        + " from {oj (values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (2, 'b')) as t2 (id, text) on (t1.id = t2.id) }");
  }
  
  @Test
  public void testOuterJoinSyntaxWithMultipleJoinsAndWithOj()
      throws Exception
  {
    testOuterJoinSyntax(
        "select *"
        + " from {oj (values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (2, 'b')) as t2 (id, text) on (t1.id = t2.id)"
        + " left outer join (values (1, '1'), (2, '2')) as t3 (id, text) on (t2.id = t3.id)}");
  }
  
  @Test
  public void testOuterJoinSyntaxWithMultipleJoinsAndWithOj2()
      throws Exception
  {
    // multiple joins with oj and missing space character after oj
    testOuterJoinSyntax(
        "select *"
        + " from {oj(values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (2, 'b')) as t2 (id, text) on (t1.id = t2.id)"
        + " left outer join (values (1, '1'), (2, '2')) as t3 (id, text) on (t2.id = t3.id)}");
  }
  
  @Test
  public void testOuterJoinSyntaxWithMultipleJoinsAndWithOj3()
      throws Exception
  {
    // multiple joins with oj and missing space character after oj and some more parenthesis
    testOuterJoinSyntax(
        "select *"
        + " from {oj(((values (1, 'one'), (2, 'two')) as t1 (id, text)"
        + " left outer join (values (1, 'a'), (2, 'b')) as t2 (id, text) on (t1.id = t2.id))"
        + " left outer join (values (1, '1'), (2, '2')) as t3 (id, text) on (t2.id = t3.id))}");
  }

  /**
   * Executes the statment.
   *
   * @param theQuery the query to execute
   * @throws Exception
   */
  private void testOuterJoinSyntax(final String theQuery)
      throws Exception
  {
    final Statement _st = connection.createStatement();
    try
    {
      final ResultSet _rs = _st.executeQuery(theQuery);
      try
      {
        int _count = 0;
        while (_rs.next())
          _count++;
        Assert.assertEquals(_count, 2);
      }
      finally
      {
        _rs.close();
      }
    }
    finally
    {
      _st.close();
    }
  }

}