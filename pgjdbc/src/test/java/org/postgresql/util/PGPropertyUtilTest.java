/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;

import org.junit.jupiter.api.Test;

import java.util.Properties;

class PGPropertyUtilTest {

  @Test
  void propertiesConsistencyCheck() {
    // port
    Properties properties = new Properties();
    PGProperty.PORT.set(properties, "0");
    assertFalse(PGPropertyUtil.propertiesConsistencyCheck(properties));
    PGProperty.PORT.set(properties, "1");
    assertTrue(PGPropertyUtil.propertiesConsistencyCheck(properties));
    PGProperty.PORT.set(properties, "5432");
    assertTrue(PGPropertyUtil.propertiesConsistencyCheck(properties));
    PGProperty.PORT.set(properties, "65535");
    assertTrue(PGPropertyUtil.propertiesConsistencyCheck(properties));
    PGProperty.PORT.set(properties, "65536");
    assertFalse(PGPropertyUtil.propertiesConsistencyCheck(properties));
    PGProperty.PORT.set(properties, "abcdef");
    assertFalse(PGPropertyUtil.propertiesConsistencyCheck(properties));
    // any other not handled
    properties = new Properties();
    properties.setProperty("not-handled-key", "not-handled-value");
    assertTrue(PGPropertyUtil.propertiesConsistencyCheck(properties));
  }

}
