/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;

/**
 * Test cases to make sure the parameterlist implementation works as expected.
 *
 * @author Jeremy Whiting jwhiting@redhat.com
 *
 */
public class V3ParameterListTests {
  private TypeTransferModeRegistry transferModeRegistry;

  @Before
  public void setUp() throws Exception {
    transferModeRegistry = new TypeTransferModeRegistry() {
        @Override
        public boolean useBinaryForSend(int oid) {
          return false;
        }

        @Override
        public boolean useBinaryForReceive(int oid) {
          return false;
        }
    };
  }

  /**
   * Test to check the merging of two collections of parameters. All elements
   * are kept.
   *
   * @throws SQLException
   *           raised exception if setting parameter fails.
   */
  @Test
  public void testMergeOfParameterLists() throws SQLException {
    SimpleParameterList s1SPL = new SimpleParameterList(8, transferModeRegistry);
    s1SPL.setIntParameter(1, 1);
    s1SPL.setIntParameter(2, 2);
    s1SPL.setIntParameter(3, 3);
    s1SPL.setIntParameter(4, 4);

    SimpleParameterList s2SPL = new SimpleParameterList(4, transferModeRegistry);
    s2SPL.setIntParameter(1, 5);
    s2SPL.setIntParameter(2, 6);
    s2SPL.setIntParameter(3, 7);
    s2SPL.setIntParameter(4, 8);

    s1SPL.appendAll(s2SPL);
    assertEquals(
        "Expected string representation of values does not match outcome.",
        "<[1 ,2 ,3 ,4 ,5 ,6 ,7 ,8]>", s1SPL.toString());
  }
}
