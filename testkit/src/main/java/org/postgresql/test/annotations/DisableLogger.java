/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.annotations;

import org.postgresql.test.impl.DisableLoggerExtension;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An annotation used to disable specific loggers during the execution of tests.
 * This annotation can suppress log output for loggers associated with specified
 * classes or categories, making it useful for cleaner and more focused test outputs.
 *
 * <p>This annotation must be used with the {@code DisableLoggerExtension} for it to take effect.
 * The extension intercepts the test lifecycle to temporarily modify logging levels for the
 * specified loggers and restores them once the test execution is complete.
 *
 * <p>The annotation supports:
 * <ul>
 * <li>Specifying classes whose associated loggers are to be disabled using the {@code value} attribute.</li>
 * <li>Specifying logger categories by name using the {@code categories} attribute.</li>
 * </ul>
 *
 * <p>Attributes:
 * <ul>
 * <li>{@code value(): Class<?>[]}: Specifies the classes whose associated loggers should be disabled.</li>
 * <li>{@code categories(): String[]}: Defines the logger categories (by string names) to disable.</li>
 * </ul>
 */
@Repeatable(DisableLogger.List.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@ExtendWith(DisableLoggerExtension.class)
public @interface DisableLogger {
  /**
   * Specifies the classes whose associated loggers should be disabled during the execution of a test or test class.
   * By default, no classes are specified, meaning no loggers will be disabled unless explicitly defined.
   *
   * @return an array of classes whose associated loggers will be disabled
   */
  Class<?>[] value() default {};

  /**
   * Specifies the logger categories (by name) that should have their loggers disabled
   * during the execution of a test or test class. This attribute is useful for disabling
   * loggers by specific category names rather than by class association.
   *
   * @return an array of logger category names to disable
   */
  String[] categories() default {};

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.METHOD, ElementType.TYPE})
  @interface List {
    DisableLogger[] value();
  }
}
