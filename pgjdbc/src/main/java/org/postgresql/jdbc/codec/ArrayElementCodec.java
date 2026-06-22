/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

/**
 * Optional capability a scalar codec implements to offer a typed,
 * allocation-free leaf for arrays of its element type (for example
 * {@code int[]} / {@code Integer[]} for {@code int4}).
 *
 * <p>The single {@link ArrayCodec} consults this when encoding or decoding an
 * array: if the element type's codec implements {@code ArrayElementCodec}, the
 * array runs through the element's {@link #arrayLeaf() leaf} and the shared
 * {@link MultiDimArrayBinary} / {@link MultiDimArrayText} walkers, so primitive
 * arrays avoid boxing. Element types without this capability fall back to
 * {@link GenericArrayLeafCodec}, which delegates each element to the element's
 * scalar codec.</p>
 *
 * <p>This keeps the array model single — one {@code ArrayCodec} for every array
 * type — while letting hot built-in element types opt into a fast path without
 * a separate per-element array codec.</p>
 */
interface ArrayElementCodec {

  /**
   * Returns the leaf strategy that reads and writes one 1-D slice of this
   * element type.
   *
   * @return the array leaf for this element type
   */
  ArrayLeafCodec arrayLeaf();
}
