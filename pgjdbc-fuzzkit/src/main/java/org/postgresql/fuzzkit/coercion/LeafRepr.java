/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit.coercion;

/**
 * How an {@link ArrayDescriptor} represents its leaf elements: the second axis of an array's target
 * class, alongside the number of dimensions. A leaf may be {@link #BOXED} (a wrapper such as
 * {@code Integer}) or {@link #PRIMITIVE} (the wrapper's primitive such as {@code int}), so an
 * {@code int4[][]} decodes to either {@code Integer[][]} or {@code int[][]}.
 *
 * <p>The two representations carry different NULL contracts on the codec wire: a boxed leaf reads a
 * wire NULL back as {@code null}, whereas a primitive leaf has no null form and refuses it. The codec
 * round-trip therefore fuzzes the primitive leaf with non-null values only (the recommended step of
 * the C3 brief); the NULL-into-primitive refusal is a separate oracle track. A scalar type whose
 * natural class has no primitive ({@code text}, {@code bytea}, {@code numeric}) offers {@link #BOXED}
 * only.
 */
public enum LeafRepr {
  /** A wrapper leaf, such as {@code Integer} -- accepts and reads back a wire NULL as {@code null}. */
  BOXED,
  /** A primitive leaf, such as {@code int} -- has no null form and refuses a wire NULL. */
  PRIMITIVE
}
