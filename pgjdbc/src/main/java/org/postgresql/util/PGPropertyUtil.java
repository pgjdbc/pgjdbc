/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.postgresql.PGProperty;

import java.util.logging.Logger;

/**
 * routines to support PG properties
 */
class PGPropertyUtil {

  private static final Logger LOGGER = Logger.getLogger(PGPropertyUtil.class.getName());

  /**
   * translate PGSERVICEFILE keys host, port, dbname
   * Example: "host" becomes "PGHOST"
   *
   * @param serviceKey key in pg_service.conf
   * @return translated property or the same value if translation is not needed
   */
  // translate PGSERVICEFILE keys host, port, dbname
  static String translatePGServiceToPGProperty(String serviceKey) {
    String testKey = "PG" + serviceKey.toUpperCase();
    if (
        PGProperty.PG_HOST.getName().equals(testKey)
            || (PGProperty.PG_PORT.getName().equals(testKey))
            || (PGProperty.PG_DBNAME.getName().equals(testKey))
    ) {
      return testKey;
    } else {
      return serviceKey;
    }
  }

  /**
   * translate PGSERVICEFILE keys host, port, dbname
   * Example: "PGHOST" becomes "host"
   *
   * @param propertyKey postgres property
   * @return translated property or the same value if translation is not needed
   */
  static String translatePGPropertyToPGService(String propertyKey) {
    if (
        PGProperty.PG_HOST.getName().equals(propertyKey)
            || (PGProperty.PG_PORT.getName().equals(propertyKey))
            || (PGProperty.PG_DBNAME.getName().equals(propertyKey))
    ) {
      return propertyKey.substring(2).toLowerCase();
    } else {
      return propertyKey;
    }
  }
}
