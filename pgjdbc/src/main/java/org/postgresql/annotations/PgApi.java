/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the lifecycle status of an API element (driver class, method,
 * or {@link org.postgresql.PGProperty} value) so that the documentation
 * generator and IDE tooling can communicate stability guarantees to users.
 *
 * <p>The annotation tracks three orthogonal axes:
 * <ul>
 *   <li>{@link #status()} — the current stability level;
 *   <li>{@link #introducedIn()} — the pgjdbc version in which the element
 *       first became available; set once at introduction and never changed
 *       afterward;
 *   <li>{@link #deprecatedIn()} and {@link #hiddenIn()} — versions in which
 *       the element transitioned to the corresponding status.
 * </ul>
 *
 * <p>The separation of {@code introducedIn} from {@code deprecatedIn} and
 * {@code hiddenIn} preserves the full lifecycle history: documentation can
 * surface, for example, "Available since 9.4; deprecated in 42.6.0; hidden
 * in 42.8.0" without losing the introduction version.
 *
 * <p>{@code @Retention(CLASS)} keeps the annotation out of the runtime
 * reflection surface (so user code cannot accidentally couple to it) but
 * preserves it in the {@code .class} files so that the documentation
 * generator's bytecode reader (ASM) can pick it up.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * @PgApi(status = STABLE, introducedIn = "9.4-1200")
 * @PgTags(PgTags.Tag.SSL)
 * @PgPropertyType(PgPropertyType.Kind.ENUM)
 * SSL_MODE("sslmode", "prefer", ...);
 *
 * @PgApi(status = DEPRECATED, introducedIn = "9.4", deprecatedIn = "42.6.0")
 * @Deprecated
 * SSL_FACTORY_ARG("sslfactoryarg", null, ...);
 *
 * @PgApi(status = HIDDEN, introducedIn = "9.4",
 *        deprecatedIn = "42.5.0", hiddenIn = "42.8.0")
 * @Deprecated
 * LEGACY_AUTH_MODE(...);
 * }</pre>
 *
 * @see PgTags
 * @see PgPropertyType
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR,
         ElementType.FIELD, ElementType.PACKAGE})
public @interface PgApi {

  /**
   * Current stability status of the annotated element.
   */
  Status status();

  /**
   * Version in which the element first appeared in pgjdbc. Set once on
   * introduction and never changed afterward, regardless of subsequent
   * status transitions.
   *
   * <p>Format is the pgjdbc release identifier as published (e.g.
   * {@code "9.4"}, {@code "9.4-1200"}, {@code "42.7.4"}).
   */
  String introducedIn();

  /**
   * Version in which the element became {@link Status#DEPRECATED}.
   *
   * <p>Required when {@link #status()} is {@code DEPRECATED} or
   * {@code HIDDEN}; otherwise empty. Validated by the documentation
   * generator.
   */
  String deprecatedIn() default "";

  /**
   * Version in which the element became {@link Status#HIDDEN}.
   *
   * <p>Required when {@link #status()} is {@code HIDDEN}; otherwise
   * empty. Validated by the documentation generator.
   */
  String hiddenIn() default "";

  /**
   * Lifecycle stage of an API element.
   */
  enum Status {

    /**
     * Not part of the published API. Subject to change or removal at
     * any time without notice. Tooling should hide internal elements
     * from end-user documentation.
     */
    INTERNAL,

    /**
     * Newly introduced, exposed to gather feedback. May change in
     * backwards-incompatible ways or be withdrawn before reaching
     * {@link #MAINTAINED} or {@link #STABLE}. Use with caution.
     */
    EXPERIMENTAL,

    /**
     * Will not change in a backwards-incompatible way within the
     * current major version line. If scheduled for removal, will be
     * demoted to {@link #DEPRECATED} first.
     */
    MAINTAINED,

    /**
     * Will not change in a backwards-incompatible way within the
     * current major version. Strictest stability promise pgjdbc
     * provides.
     */
    STABLE,

    /**
     * Should no longer be used. Still callable from source code so
     * that existing applications continue to compile; the compiler
     * emits a deprecation warning. Document the recommended
     * replacement in the surrounding javadoc.
     *
     * <p>By convention, a declaration with this status also carries
     * the standard {@link java.lang.Deprecated} annotation so that
     * IDEs and the compiler also recognise the deprecation. The
     * documentation generator enforces this pairing.
     */
    DEPRECATED,

    /**
     * Source-level soft removal. The declaration remains in the
     * compiled jar so that previously-compiled code continues to link
     * and run (binary compatibility is preserved), but javac and IDEs
     * are expected to skip the declaration during name resolution so
     * that new source code cannot reference it.
     *
     * <p>The mechanism that achieves this — setting the
     * {@code ACC_SYNTHETIC} bit on the corresponding bytecode element
     * — is implemented in a separate post-compile build step and is
     * not part of this annotation's contract.
     */
    HIDDEN,
  }
}
