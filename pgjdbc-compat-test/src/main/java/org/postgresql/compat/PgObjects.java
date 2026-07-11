/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.compat;

/**
 * Builds a {@code org.postgresql.util.PGobject} in a chosen class loader, so the differential oracle can
 * feed a driver-specific input type to each driver.
 *
 * <p>A {@code PGobject} is bound with {@code setObject} and, being an {@code org.postgresql.*} class,
 * exists separately in the current and the baseline loader. Each driver must receive an instance from its
 * own loader; an instance from the other loader would fail a cross-loader cast inside the driver. Build
 * the current-side value from {@code PgObjects.class.getClassLoader()} and the baseline-side value from
 * {@link LegacyDriverLoader#classLoader()}.
 */
public final class PgObjects {
  private PgObjects() {
  }

  /** Creates a {@code PGobject} with the given {@code type} and {@code value} in {@code loader}. */
  public static Object of(ClassLoader loader, String type, String value)
      throws ReflectiveOperationException {
    Class<?> pgObjectClass = loader.loadClass("org.postgresql.util.PGobject");
    Object pgObject = pgObjectClass.getDeclaredConstructor().newInstance();
    pgObjectClass.getMethod("setType", String.class).invoke(pgObject, type);
    pgObjectClass.getMethod("setValue", String.class).invoke(pgObject, value);
    return pgObject;
  }
}
