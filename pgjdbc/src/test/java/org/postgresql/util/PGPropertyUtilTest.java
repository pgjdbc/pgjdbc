/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;

import org.junit.jupiter.api.Test;

import java.util.Properties;

class PGPropertyUtilTest {

  @Test
  void propertiesConsistencyCheck() {
    // PGPORT
    Properties properties = new Properties();
    PGProperty.PG_PORT.set(properties, "0");
    assertFalse(PGPropertyUtil.propertiesConsistencyCheck(properties));
    PGProperty.PG_PORT.set(properties, "1");
    assertTrue(PGPropertyUtil.propertiesConsistencyCheck(properties));
    PGProperty.PG_PORT.set(properties, "5432");
    assertTrue(PGPropertyUtil.propertiesConsistencyCheck(properties));
    PGProperty.PG_PORT.set(properties, "65535");
    assertTrue(PGPropertyUtil.propertiesConsistencyCheck(properties));
    PGProperty.PG_PORT.set(properties, "65536");
    assertFalse(PGPropertyUtil.propertiesConsistencyCheck(properties));
    PGProperty.PG_PORT.set(properties, "abcdef");
    assertFalse(PGPropertyUtil.propertiesConsistencyCheck(properties));
    // any other not handled
    properties = new Properties();
    properties.setProperty("not-handled-key", "not-handled-value");
    assertTrue(PGPropertyUtil.propertiesConsistencyCheck(properties));
  }

  // data for next two test methods
  private static final String[][] TRANSLATION_TABLE = {
      {"allowEncodingChanges", "allowEncodingChanges"},
      {"port", "PGPORT"},
      {"host", "PGHOST"},
      {"dbname", "PGDBNAME"},
  };

  @Test
  void translatePGServiceToPGProperty() {
    for (String[] row : TRANSLATION_TABLE) {
      assertEquals(row[1], PGPropertyUtil.translatePGServiceToPGProperty(row[0]));
    }
  }

  @Test
  void translatePGPropertyToPGService() {
    for (String[] row : TRANSLATION_TABLE) {
      assertEquals(row[0], PGPropertyUtil.translatePGPropertyToPGService(row[1]));
    }
  }
}
