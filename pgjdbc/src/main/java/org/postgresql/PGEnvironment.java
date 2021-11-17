/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Some environment variables are intended to have same meaning as libpq describes here:
 * https://www.postgresql.org/docs/current/libpq-envars.html
 */
public enum PGEnvironment {

  /**
   * Specified location of password file.
   */
  ORG_POSTGRESQL_PGPASSFILE(
      "org.postgresql.pgpassfile",
      null,
      "Specified location of password file."),

  /**
   * Specified location of password file.
   */
  PGPASSFILE(
      "PGPASSFILE",
      "pgpass",
      "Specified location of password file."),

  /**
   * The connection service resource (file, url) allows connection parameters to be associated
   * with a single service name.
   */
  ORG_POSTGRESQL_PGSERVICEFILE(
      "org.postgresql.pgservicefile",
      null,
      "Specifies the service resource to resolve connection properties."),

  /**
   * The connection service resource (file, url) allows connection parameters to be associated
   * with a single service name.
   */
  PGSERVICEFILE(
      "PGSERVICEFILE",
      "pg_service.conf",
      "Specifies the service resource to resolve connection properties."),

  /**
   * sets the directory containing the PGSERVICEFILE file and possibly other system-wide
   * configuration files.
   */
  PGSYSCONFDIR(
      "PGSYSCONFDIR",
      null,
      "Specifies the directory containing the PGSERVICEFILE file"),
  ;

  private final String name;
  private final @Nullable String defaultValue;
  private final String description;

  PGEnvironment(String name, @Nullable String defaultValue, String description) {
    this.name = name;
    this.defaultValue = defaultValue;
    this.description = description;
  }

  private static final Map<String, PGEnvironment> PROPS_BY_NAME = new HashMap<>();

  static {
    for (PGEnvironment prop : PGEnvironment.values()) {
      if (PROPS_BY_NAME.put(prop.getName(), prop) != null) {
        throw new IllegalStateException("Duplicate PGProperty name: " + prop.getName());
      }
    }
  }

  /**
   * Returns the name of the parameter.
   *
   * @return the name of the parameter
   */
  public String getName() {
    return name;
  }

  /**
   * Returns the default value for this parameter.
   *
   * @return the default value for this parameter or null
   */
  public @Nullable String getDefaultValue() {
    return defaultValue;
  }

  /**
   * Returns the description for this parameter.
   *
   * @return the description for this parameter
   */
  public String getDescription() {
    return description;
  }

}
