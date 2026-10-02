/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.annotations.DisableLogger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Properties;

class PGPropertyUtilTest {

  @Test
  void propertiesConsistencyCheck() {
    // PGPORT
    Properties properties = new Properties();
    PGProperty.PG_PORT.set(properties, "1");
    assertTrue(PGPropertyUtil.propertiesConsistencyCheck(properties));
    PGProperty.PG_PORT.set(properties, "5432");
    assertTrue(PGPropertyUtil.propertiesConsistencyCheck(properties));
    PGProperty.PG_PORT.set(properties, "65535");
    assertTrue(PGPropertyUtil.propertiesConsistencyCheck(properties));
    // any other not handled
    properties = new Properties();
    properties.setProperty("not-handled-key", "not-handled-value");
    assertTrue(PGPropertyUtil.propertiesConsistencyCheck(properties));
  }

  @Test
  @DisableLogger(PGPropertyUtil.class)
  void invalidPortCheck() {
    // PGPORT
    Properties properties = new Properties();
    PGProperty.PG_PORT.set(properties, "0");
    assertFalse(PGPropertyUtil.propertiesConsistencyCheck(properties));
    PGProperty.PG_PORT.set(properties, "65536");
    assertFalse(PGPropertyUtil.propertiesConsistencyCheck(properties));
    PGProperty.PG_PORT.set(properties, "abcdef");
    assertFalse(PGPropertyUtil.propertiesConsistencyCheck(properties));
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

  @ParameterizedTest
  @ValueSource(strings = {"password", "sslpassword", "oauthToken", "oauthClientSecret"})
  void masksCredentialValue(String propertyName) {
    PGProperty property = PGProperty.forName(propertyName);
    assertNotNull(property, propertyName);
    assertTrue(property.isSensitive(), () -> propertyName + " should be sensitive");
    assertEquals(
        "jdbc:postgresql://localhost:5432/test?" + propertyName + "=***",
        PGPropertyUtil.maskSensitiveValues(
            "jdbc:postgresql://localhost:5432/test?" + propertyName + "=s3cr3t"));
  }

  @Test
  void keepsNonSensitiveValues() {
    assertEquals(
        "jdbc:postgresql://localhost:5432/test?user=alice&oauthToken=***"
            + "&oauthClientId=pgjdbc&sslmode=disable",
        PGPropertyUtil.maskSensitiveValues(
            "jdbc:postgresql://localhost:5432/test?user=alice&oauthToken=eyJhbGciOi"
                + "&oauthClientId=pgjdbc&sslmode=disable"));
  }

  @Test
  void urlWithoutQueryStringIsUnchanged() {
    assertEquals("jdbc:postgresql://localhost:5432/test",
        PGPropertyUtil.maskSensitiveValues("jdbc:postgresql://localhost:5432/test"));
  }

  @Test
  void masksUnusualQueryString() {
    // An empty value still gets masked, so that the absence of a value is not disclosed either
    assertEquals("jdbc:postgresql:///?password=***",
        PGPropertyUtil.maskSensitiveValues("jdbc:postgresql:///?password="));
    // Parameters without a value, unknown parameters and empty parameters are passed through
    assertEquals("jdbc:postgresql:///?password&unknownParam=x&&ssl=true",
        PGPropertyUtil.maskSensitiveValues(
            "jdbc:postgresql:///?password&unknownParam=x&&ssl=true"));
    // Only the first "=" separates name from value, and non-sensitive values are left alone
    assertEquals("jdbc:postgresql:///?oauthToken=***&options=-c%20a%3Db",
        PGPropertyUtil.maskSensitiveValues(
            "jdbc:postgresql:///?oauthToken=ey=JhbGciOi&options=-c%20a%3Db"));
    // Not a URL at all
    assertEquals("", PGPropertyUtil.maskSensitiveValues(""));
  }
}
