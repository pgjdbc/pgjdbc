/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.annotations;

import org.postgresql.test.impl.EnabledForServerVersionRangeCondition;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Only enables a test if the current server version matches the specified range
 * @see org.junit.jupiter.api.Disabled
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(EnabledForServerVersionRangeCondition.class)
public @interface EnabledForServerVersionRange {
  /**
   * Less than
   */
  String lt() default "";

  /**
   * Less than or equal
   */
  String lte() default "";

  /**
   * Greater than or equal
   */
  String gte() default "";

  /**
   * Greater than
   */
  String gt() default "";
}
