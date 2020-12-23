/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

public enum JavaVersion {
  // Note: order is important,
  v1_8(2),
  other(1);

  private static final JavaVersion RUNTIME_VERSION = from(System.getProperty("java.version"));

  /**
   * Returns enum value that represents current runtime. For instance, when using -jre7.jar via Java
   * 8, this would return v18
   *
   * @return enum value that represents current runtime.
   */
  public static JavaVersion getRuntimeVersion() {
    return RUNTIME_VERSION;
  }

  /**
   * Java version string like in {@code "java.version"} property.
   *
   * @param version string like 1.6, 1.7, etc
   * @return JavaVersion enum
   */
  public static JavaVersion from(String version) {
    if (version.startsWith("1.8")) {
      return v1_8;
    }
    return other;
  }

  private final int sizeMultiple;

  private JavaVersion(int sizeMultiple) {
    this.sizeMultiple = sizeMultiple;
  }

  /**
   * Provides a version specific estimate of memory consumed by <i>string</i>.
   *
   * @param string The {@code String} instance to estimate memory consumption for.
   * @return Approximate memory used by <i>string</i>.
   */
  public final int size(String string) {
    return string != null ? string.length() * sizeMultiple : 0;
  }
}
