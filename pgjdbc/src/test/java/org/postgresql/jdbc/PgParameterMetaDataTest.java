/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Oid;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class PgParameterMetaDataTest {

  private PgParameterMetaData pgParameterMetaData;
  private int[] oids;

  /**
   * Simple setup to create with PgParameterMetaData object with no connection.
   */
  @Before
  public void setUp() {
    oids = new int[]{Oid.VOID, Oid.UNSPECIFIED, Oid.BIT, Oid.VARBIT_ARRAY};
    pgParameterMetaData = new PgParameterMetaData(null, oids);
  }

  /**
   * Test for case of retrieving oids array from PgParameterMetaData object. Checks if returned
   * array is the same as array set inside pgParameterMetaData object.
   */
  @Test
  public void testGetOids() {
    int[] actualOids = pgParameterMetaData.getOids();

    Assert.assertArrayEquals(oids, actualOids);
  }
}
