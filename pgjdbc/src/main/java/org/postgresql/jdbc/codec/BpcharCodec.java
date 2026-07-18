/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

/**
 * Codec for PostgreSQL bpchar (CHARACTER) type.
 *
 * <p>bpchar is a blank-padded fixed-length character type; the padding is a server-side effect and its wire
 * is the charset text in both formats, so it shares {@link AbstractTextCodec}'s logic under a different type
 * name.</p>
 */
public final class BpcharCodec extends AbstractTextCodec {

  public static final BpcharCodec INSTANCE = new BpcharCodec();

  private BpcharCodec() {
    super("bpchar");
  }
}
