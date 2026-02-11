/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.annotations;

import org.postgresql.test.impl.ServerVersionGreaterCondition;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Disables test if the current server version is greater than specified version.
 * @see org.junit.jupiter.api.Disabled
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ServerVersionGreaterCondition.class)
public @interface DisabledIfServerVersionGreater {
  /**
   * @return not null sever version in form x.y.z like 9.4, 9.5.3, etc.
   * @see org.postgresql.core.ServerVersion
   */
  String value();
}
