/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the annotated API element is experimental and may change
 * in future releases without prior notice.
 *
 * <p>Experimental APIs are not subject to the same compatibility guarantees
 * as stable APIs. They may be modified, deprecated, or removed in any future
 * release.</p>
 *
 * <p>Users are encouraged to provide feedback on experimental APIs to help
 * shape their final form.</p>
 *
 * @since 42.8.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
    ElementType.TYPE,
    ElementType.METHOD,
    ElementType.FIELD,
    ElementType.CONSTRUCTOR,
    ElementType.PACKAGE
})
public @interface Experimental {
  /**
   * Optional description of what aspects of the API are experimental
   * or what changes might be expected.
   *
   * @return description of the experimental nature
   */
  String value() default "";
}
