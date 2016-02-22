/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2003-2016, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.core.v2;

import org.postgresql.test.jdbc2.BaseTest;

import java.sql.SQLException;

/**
 * Test cases to make sure the parameterlist implementation works as expected.
 *
 * @author Jeremy Whiting jwhiting@redhat.com
 *
 */
public class V2ParameterListTests extends BaseTest {

  /**
   * Test to check the merging of two parameter lists.
   *
   * @throws SQLException fault raised when setting parameter
   */
  public void testMergeOfParameterLists() throws SQLException {
    SimpleParameterList o1SPL = new SimpleParameterList(8, Boolean.TRUE);
    o1SPL.setIntParameter(1, 1);
    o1SPL.setIntParameter(2, 2);
    o1SPL.setIntParameter(3, 3);
    o1SPL.setIntParameter(4, 4);

    SimpleParameterList s2SPL = new SimpleParameterList(4, Boolean.TRUE);
    s2SPL.setIntParameter(1, 5);
    s2SPL.setIntParameter(2, 6);
    s2SPL.setIntParameter(3, 7);
    s2SPL.setIntParameter(4, 8);

    o1SPL.appendAll(s2SPL);

    assertEquals(
        "Expected string representation of parameter list does not match product.",
        "<[1 ,2 ,3 ,4 ,5 ,6 ,7 ,8]>", o1SPL.toString());
  }

  public V2ParameterListTests(String test) {
    super(test);
  }
}
