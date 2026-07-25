/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGProperty;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

class ClassLoaderStrategyTest {

  @Test
  void defaultsToDriverFirstWhenAbsent() throws Exception {
    assertEquals(ClassLoaderStrategy.DRIVER_FIRST, ClassLoaderStrategy.of(new Properties()));
  }

  @Test
  void parsesEachValueIgnoringCase() throws Exception {
    Properties info = new Properties();
    PGProperty.CLASS_LOADER_STRATEGY.set(info, "DRIVER");
    assertEquals(ClassLoaderStrategy.DRIVER, ClassLoaderStrategy.of(info));
    PGProperty.CLASS_LOADER_STRATEGY.set(info, "driver-first");
    assertEquals(ClassLoaderStrategy.DRIVER_FIRST, ClassLoaderStrategy.of(info));
    PGProperty.CLASS_LOADER_STRATEGY.set(info, "Context-First");
    assertEquals(ClassLoaderStrategy.CONTEXT_FIRST, ClassLoaderStrategy.of(info));
  }

  @Test
  void rejectsUnknownValue() {
    Properties info = new Properties();
    PGProperty.CLASS_LOADER_STRATEGY.set(info, "nonsense");
    assertThrows(PSQLException.class, () -> ClassLoaderStrategy.of(info));
  }

  @Test
  void ordersClassLoadersPerStrategy() {
    ClassLoader driver = new ClassLoader() {
    };
    ClassLoader context = new ClassLoader() {
    };
    assertEquals(Collections.singletonList(driver),
        ClassLoaderStrategy.DRIVER.classLoaders(driver, context));
    assertEquals(Arrays.asList(driver, context),
        ClassLoaderStrategy.DRIVER_FIRST.classLoaders(driver, context));
    assertEquals(Arrays.asList(context, driver),
        ClassLoaderStrategy.CONTEXT_FIRST.classLoaders(driver, context));
  }

  @Test
  void dropsNullClassLoaders() {
    ClassLoader driver = new ClassLoader() {
    };
    assertEquals(Collections.singletonList(driver),
        ClassLoaderStrategy.DRIVER_FIRST.classLoaders(driver, null));
    assertEquals(Collections.singletonList(driver),
        ClassLoaderStrategy.CONTEXT_FIRST.classLoaders(null, driver));
    assertEquals(Collections.emptyList(),
        ClassLoaderStrategy.DRIVER.classLoaders(null, null));
  }
}
