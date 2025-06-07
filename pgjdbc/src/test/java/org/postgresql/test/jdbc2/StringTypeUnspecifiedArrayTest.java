/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.PGProperty;
import org.postgresql.geometric.PGbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

@ParameterizedClass
@MethodSource("data")
public class StringTypeUnspecifiedArrayTest extends BaseTest4 {
  public StringTypeUnspecifiedArrayTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
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

  @Test
  public void testCreateArrayWithNonCachedType() throws Exception {
    PGbox[] in = new PGbox[0];
    Array a = con.createArrayOf("box", in);
    assertEquals(1111, a.getBaseType());
  }
}
