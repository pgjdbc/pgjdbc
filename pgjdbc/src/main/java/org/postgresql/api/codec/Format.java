/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

/**
 * The wire format of an encoded PostgreSQL value: the {@link #TEXT} representation a human can read,
 * or the compact {@link #BINARY} representation.
 *
 * <p>Most codecs read and write both. {@link BinaryCodec#decodesBinary()} and
 * {@link TextCodec#decodesText()} report which one a given codec actually decodes, which the
 * offline and {@code COPY} paths consult because they have no format negotiation to fall back on.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public enum Format {
  /** The text wire format, PostgreSQL format code {@code 0}. */
  TEXT,

  /** The binary wire format, PostgreSQL format code {@code 1}. */
  BINARY
}
