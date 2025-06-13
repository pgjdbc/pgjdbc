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
   * The database name.
   */
  ORG_POSTGRESQL_PGDATABASE(
      "org.postgresql.pgdatabase",
      null,
      "Specifies the database of instance."),

  /**
   * The database name.
   */
  PGDATABASE(
      "PGDATABASE",
      null,
      "Specifies the database of instance."),

  /**
   * Name of host to connect to.
   */
  ORG_POSTGRESQL_PGHOST(
      "org.postgresql.pghost",
      null,
      "Specifies the host of instance."),

  /**
   * Name of host to connect to.
   */
  PGHOST(
      "PGHOST",
      null,
      "Specifies the host of instance."),

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
      null,
      "Specified location of password file."),

  /**
   * The password of user.
   */
  ORG_POSTGRESQL_PGPASSWORD(
      "org.postgresql.pgpassword",
      null,
      "Specifies the password of instance."),

  /**
   * The password of user.
   */
  PGPASSWORD(
      "PGPASSWORD",
      null,
      "Specifies the password of instance."),

  /**
   * Port number to connect to at the server host.
   */
  ORG_POSTGRESQL_PGPORT(
      "org.postgresql.pgport",
      null,
      "Specifies the port of instance."),

  /**
   * Port number to connect to at the server host.
   */
  PGPORT(
      "PGPORT",
      null,
      "Specifies the port of instance."),

  /**
   * The connection service name to be found in PGSERVICEFILE.
   */
  ORG_POSTGRESQL_PGSERVICE(
      "org.postgresql.pgservice",
      null,
      "Specifies the service name."),

  /**
   * The connection service name to be found in PGSERVICEFILE.
   */
  PGSERVICE(
      "PGSERVICE",
      null,
      "Specifies the service name."),

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

  /**
   * The connection user.
   */
  ORG_POSTGRESQL_PGUSER(
      "org.postgresql.pguser",
      null,
      "Specifies the user of instance."),

  /**
   * The connection user.
   */
  PGUSER(
      "PGUSER",
      null,
      "Specifies the user of instance."),
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

  public static @Nullable PGEnvironment forName(String name) {
    return PROPS_BY_NAME.get(name);
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
   * @deprecated instead of PGSERVICEFILE.getDefaultValue() use OSUtil.getDefaultPgServiceFilename(),
   *             getDefaultValue() returns null for all other enum values.
   */
  @Deprecated
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

  public @Nullable String readStringValue() {
    if (this.getName().startsWith("org.postgresql.")) {
      return System.getProperty(this.getName());
    } else {
      return System.getenv().get(this.getName());
    }
  }
}
