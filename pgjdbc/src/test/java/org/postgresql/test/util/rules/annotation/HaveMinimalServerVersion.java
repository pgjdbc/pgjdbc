/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util.rules.annotation;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation use to ignore test if the current server version less than specified version.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = {METHOD, TYPE})
public @interface HaveMinimalServerVersion {
  /**
   * @return not null sever version in form x.y.z like 9.4, 9.5.3, etc.
   * @see org.postgresql.core.ServerVersion
   */
  String value();
}
