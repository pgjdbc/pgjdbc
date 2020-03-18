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
   * Test to check work of getFields() method.
   */
  @Test
  public void testGetFields() {
    Assert.assertArrayEquals(fields, pgResultSetMetaData.getFields());
  }

}
