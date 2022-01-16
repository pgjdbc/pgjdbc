/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PGPropertyUtilTest {

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
