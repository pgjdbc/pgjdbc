/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.impl;

import org.postgresql.test.annotations.DisableLogger;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A JUnit extension that disables specific loggers during the execution of tests annotated
 * with {@code @DisableLogger}. This can be used to suppress log output for certain categories
 * or classes during the test execution.
 *
 * <p>This extension handles the following responsibilities:
 * <ul>
 * <li>Before each test, it identifies loggers specified in {@code @DisableLogger} annotations
 *   on the test method or test class, saves their current logging levels, and disables them
 *   by setting their levels to {@link Level#OFF}.</li>
 * <li>After each test, it restores the original logging levels for the loggers that were disabled.</li>
 * </ul>
 *
 * <p>The extension utilizes the JUnit 5 extension model, implementing {@link BeforeEachCallback}
 * and {@link AfterEachCallback}, for pre- and post-test processing.
 *
 * <p>This extension works with the {@code @DisableLogger} annotation, which allows specifying
 * loggers by their associated classes or by category names. Annotations can be applied
 * at the method or class level, and multiple annotations can be used together.
 *
 * <p>For internal state management, the extension uses a {@link ExtensionContext.Store}
 * to retain and retrieve the original logging levels for loggers across the lifecycle of each test.
 */
public class DisableLoggerExtension implements BeforeEachCallback, AfterEachCallback {
  static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(DisableLoggerExtension.class);
  private static final String SAVED_LEVELS_KEY = "savedLoggerLevels";

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    List<DisableLogger> annotations = new ArrayList<>();
    annotations.addAll(
        AnnotationSupport.findRepeatableAnnotations(context.getRequiredTestMethod(), DisableLogger.class));
    annotations.addAll(
        AnnotationSupport.findRepeatableAnnotations(context.getRequiredTestClass(), DisableLogger.class));

    if (annotations.isEmpty()) {
      return;
    }

    // Collect all logger names from annotations
    Set<String> loggerNames = new HashSet<>();
    for (DisableLogger annotation : annotations) {
      if (annotation.value().length == 0 && annotation.categories().length == 0) {
        throw new IllegalArgumentException("At least one logger category or class must be specified for @DisableLogger for " + context.getDisplayName());
      }
      for (Class<?> clazz : annotation.value()) {
        loggerNames.add(clazz.getName());
      }
      loggerNames.addAll(Arrays.asList(annotation.categories()));
    }

    // Save current levels and disable
    Map<String, @Nullable Level> savedLevels = new HashMap<>();
    for (String name : loggerNames) {
      Logger logger = Logger.getLogger(name);
      savedLevels.put(name, logger.getLevel());
      logger.setLevel(Level.OFF);
    }

    context.getStore(NAMESPACE).put(SAVED_LEVELS_KEY, savedLevels);
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    @SuppressWarnings("unchecked")
    Map<String, Level> savedLevels =
        (Map<String, Level>) context.getStore(NAMESPACE).remove(SAVED_LEVELS_KEY);
    if (savedLevels == null) {
      return;
    }

    for (Map.Entry<String, Level> entry : savedLevels.entrySet()) {
      Logger.getLogger(entry.getKey()).setLevel(entry.getValue());
    }
  }
}
