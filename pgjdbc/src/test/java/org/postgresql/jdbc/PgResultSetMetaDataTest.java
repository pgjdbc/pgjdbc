/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Field;
import org.postgresql.core.Oid;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class PgResultSetMetaDataTest {

  private PgResultSetMetaData pgResultSetMetaData;
  private Field[] fields;

  /**
   * Simple setup to create PgResultSetMetaData object with no connection.
   */
  @Before
  public void setUp() {
    fields = new Field[]{
      new Field("a", Oid.VOID),
      new Field("b", Oid.VARCHAR),
      new Field("c", Oid.INT2)
    };
    pgResultSetMetaData = new PgResultSetMetaData(null, fields);
  }

  /**
   * Test for case of retrieving fields array from PgResultSetMetaData object. Checks if returned
   * fields array is the same as array set in pgResultSetMetaData.
   */
  @Test
  public void testGetFields() {
    Field[] actualFields = pgResultSetMetaData.getFields();

    Assert.assertArrayEquals(fields, actualFields);
  }

}
