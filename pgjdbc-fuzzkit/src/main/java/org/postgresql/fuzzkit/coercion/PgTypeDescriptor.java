/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit.coercion;

import org.postgresql.jdbc.PgType;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * A single PostgreSQL backend type as the coercion tests see it: the per-OID source of truth for its
 * <em>axes and bindings</em>, not its driver behaviour. The dictionaries ({@link ReadCoercions},
 * {@link WriteCoercions}) still own the behaviour tables -- which reader or class a type accepts and
 * with what outcome. A descriptor holds only what those tables cannot derive, and delegates the rest.
 *
 * <p>This is a sealed hierarchy: the package-private constructor closes the set of subtypes to this
 * package (the testkit still targets Java 8, so it seals through the constructor rather than a
 * {@code sealed}/{@code permits} clause). The base holds only what every backend type shares -- its
 * {@code oid} and a polymorphic {@link #pgType()} that builds the offline {@link PgType} the codec
 * fuzzers hand to the connectionless {@code CodecContext}. Structural axes belong to the subtypes:
 *
 * <ul>
 *   <li>{@link ScalarDescriptor} -- a scalar (both a coercion scalar such as {@code int4} and a
 *       codec-only scalar such as {@code int2}), carrying the coercion fields ({@code jdbcType},
 *       {@code naturalClass}, {@code typedWriter}/{@code typedReader}, {@code fidelity}, {@code poison}).</li>
 *   <li>{@link ArrayDescriptor} -- a container over a scalar element, carrying the dimension and
 *       leaf-representation axes ({@code int4[]}, {@code int4[][]}; {@code Integer[]}, {@code int[]}).</li>
 *   <li>{@link CompositeDescriptor} -- a named row type, carrying its ordered fields.</li>
 * </ul>
 *
 * <p>The descriptor <b>derives</b> from the dictionaries, without copying their tables:
 * {@link #defaultObjectClass()} from {@link ReadCoercions#defaultObjectClass(int)}, the produced-class
 * set from the {@code readObject(Class)} row of {@link ReadCoercions}, and the accepted-class set from
 * the encode row of {@link WriteCoercions}. These need only the {@code oid}, so they live on the base.
 */
public abstract class PgTypeDescriptor {

  private final int oid;

  /** Package-private: it seals the hierarchy to the subtypes declared in this package. */
  PgTypeDescriptor(int oid) {
    this.oid = oid;
  }

  /** The PostgreSQL type OID that keys this descriptor. */
  public int oid() {
    return oid;
  }

  /**
   * The offline {@link PgType} this backend type resolves to in a connectionless {@code CodecContext}:
   * a scalar builds it from its name and {@code typcategory}. Built the way the codec unit tests build
   * their hand-written descriptors, so the fuzzers no longer keep the {@code PgType} constants inline.
   *
   * @return the offline type descriptor for the codec context
   */
  public abstract PgType pgType();

  /**
   * The class the no-arg {@code readObject()} / {@code getObject()} returns for this type under the
   * given connection config, delegated to {@link ReadCoercions#defaultObjectClass(int, Map)}.
   *
   * @param config the connection properties that scope config-dependent metadata
   * @return the default Java class, or {@code null} if the dictionary leaves it unspecified
   */
  public @Nullable Class<?> defaultObjectClass(Map<String, String> config) {
    return ReadCoercions.defaultObjectClass(oid, config);
  }

  /** The default {@code getObject} class under the default connection config. */
  public @Nullable Class<?> defaultObjectClass() {
    return ReadCoercions.defaultObjectClass(oid);
  }

  /**
   * The Java classes this type produces on the read side -- the {@code readObject(Class)} targets
   * listed for it in {@link ReadCoercions}. Derived, never stored.
   *
   * @return the produced classes, never {@code null}
   */
  public Set<Class<?>> producedClasses() {
    return ReadCoercions.objectTargets(oid);
  }

  /**
   * The Java classes this type accepts on the write side -- the source classes listed for it in
   * {@link WriteCoercions}. Derived, never stored.
   *
   * @return the accepted classes, never {@code null}
   */
  public Set<Class<?>> acceptedClasses() {
    return WriteCoercions.acceptedClasses(oid);
  }
}
