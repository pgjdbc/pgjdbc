/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2003-2016, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.core;

import org.junit.Assert;

/**
 * Tests to check the DMLCommand operates as expected.
 *
 * @author Jeremy Whiting jwhiting@redhat.com
 *
 */
public class SqlCommandTest {

  /**
   * Test to check the is returning keyword present check method provides the
   * correct return value. The create method does not parse the sql.
   * Instead the {@code org.postgresql.core.Parser} does parsing and calls
   * create with the correct value.
   */
  public void testCreateDMLCommandWithReturningProperty() {
    SqlCommand com = SqlCommand.createStatementTypeInfo(SqlCommandType.BLANK, true);
    Assert.assertTrue(com.isReturningKeywordPresent());
  }
}
