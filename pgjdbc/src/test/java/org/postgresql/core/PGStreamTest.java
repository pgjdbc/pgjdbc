/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.test.TestUtil;
import org.postgresql.util.HostSpec;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;

import javax.net.SocketFactory;

public class PGStreamTest {

  private PGStream pgStream;

  private static String pgInputVariableName = "pgInput";

  /**
   * Setup to create PGStream object.
   */
  @Before
  public void setUp() throws IOException {
    pgStream = new PGStream(SocketFactory.getDefault(),
      new HostSpec(TestUtil.getServer(), TestUtil.getPort()), 0);
  }

  /**
   * Test for case of retrieving pgInput from PGStream object. Checks if returned
   * VisibleBufferedInputStream is the same as VisibleBufferedInputStream object inside pgStream.
   */
  @Test
  public void testGetPgInput() throws NoSuchFieldException, IllegalAccessException {
    VisibleBufferedInputStream expectedPgInput = getPgInput();
    VisibleBufferedInputStream actualPgInput = pgStream.getPgInput();

    Assert.assertEquals(expectedPgInput, actualPgInput);
  }

  /**
   * Get pgInput from pgStream via reflection.
   *
   * @return pgInput variable from pgStream
   */
  private VisibleBufferedInputStream getPgInput()
      throws NoSuchFieldException, IllegalAccessException {
    Field field = pgStream.getClass().getDeclaredField(pgInputVariableName);
    field.setAccessible(true);
    return (VisibleBufferedInputStream) field.get(pgStream);
  }
}
