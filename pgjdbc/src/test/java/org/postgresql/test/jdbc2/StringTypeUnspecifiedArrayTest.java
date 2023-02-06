/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.PGProperty;
import org.postgresql.geometric.PGbox;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

@RunWith(Parameterized.class)
public class StringTypeUnspecifiedArrayTest extends BaseTest4 {

  @Before
  public void createExtension() {
    try {
      TestUtil.createExtension(con, "cube");
    } catch (Exception ex) {
      // we can ignore this as the test won't run if the extension isn't loaded
    }
  }

  @After
  public void dropExtension() {
    try {
      TestUtil.dropExtension(con, "cube");
    } catch (Exception ex) {
      // we can ignore this as the test won't run if the extension isn't loaded
    }
  }

  public StringTypeUnspecifiedArrayTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @Parameterized.Parameters(name = "binary = {0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Override
  protected void updateProperties(Properties props) {
    PGProperty.STRING_TYPE.set(props, "unspecified");
    super.updateProperties(props);
  }

  /*
  This test relies on the presence of the cube contrib extension
   */

  @Test
  public void testCreateArrayWithNonCachedType() throws Exception {
    assumeExtensionInstalled("cube");
    PGbox[] in = new PGbox[0];
    Array a = con.createArrayOf("cube", in);
    Assert.assertEquals(1111, a.getBaseType());
  }
}
