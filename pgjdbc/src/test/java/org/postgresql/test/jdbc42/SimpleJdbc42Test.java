/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertTrue;

import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;

import java.sql.PreparedStatement;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Most basic test to check that the right package is compiled
 */
public class SimpleJdbc42Test extends BaseTest4 {

  /**
   * Test presence of JDBC 4.2 specific methods
   */
  @Test
  public void testSupportsRefCursors() throws Exception {
    assertTrue(con.getMetaData().supportsRefCursors());
  }

  @Test
  public void testZonedDateTime() throws Exception {
    PreparedStatement preparedStatement = con.prepareStatement("insert into test values (?)");
    preparedStatement.setObject(1, ZonedDateTime.now(ZoneOffset.UTC));
  }
}
