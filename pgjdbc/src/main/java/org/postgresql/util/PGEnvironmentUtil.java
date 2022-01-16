/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.postgresql.PGEnvironment;
import org.postgresql.PGProperty;

/**
 * routines to support PG properties
 */
class PGEnvironmentUtil {

  /**
   * translate PGEnvironment keys to PGProperty keys
   * Example: "PGUSER" becomes "user"
   *
   * @param pgEnvironment environment
   * @return translated property
   */
  static PGProperty translateToPGProperty(PGEnvironment pgEnvironment) {
    if (PGEnvironment.PGHOST == pgEnvironment || PGEnvironment.ORG_POSTGRESQL_PGHOST == pgEnvironment) {
      return PGProperty.PG_HOST;
    } else if (PGEnvironment.PGPORT == pgEnvironment || PGEnvironment.ORG_POSTGRESQL_PGPORT == pgEnvironment) {
      return PGProperty.PG_PORT;
    } else if (PGEnvironment.PGDATABASE == pgEnvironment || PGEnvironment.ORG_POSTGRESQL_PGDATABASE == pgEnvironment) {
      return PGProperty.PG_DBNAME;
    } else if (PGEnvironment.PGUSER == pgEnvironment || PGEnvironment.ORG_POSTGRESQL_PGUSER == pgEnvironment) {
      return PGProperty.USER;
    } else if (PGEnvironment.PGPASSWORD == pgEnvironment || PGEnvironment.ORG_POSTGRESQL_PGPASSWORD == pgEnvironment) {
      return PGProperty.PASSWORD;
    }
    throw new RuntimeException(String.format("bug: unhandled value [%s]", pgEnvironment));
  }

  /**
   * Wrapper for "static PGProperty translateToPGProperty(PGEnvironment pgEnvironment)"
   *
   * @param pgEnvironment string
   * @return string
   */
  static String translateToPGProperty(String pgEnvironment) {
    PGEnvironment environment = PGEnvironment.forName(pgEnvironment);
    if (environment == null) {
      throw new RuntimeException(String.format("bug: unhandled value [%s]", pgEnvironment));
    }
    return translateToPGProperty(environment).getName();
  }
}
