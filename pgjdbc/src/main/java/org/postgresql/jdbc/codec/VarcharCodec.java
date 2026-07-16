/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

/**
 * Codec for PostgreSQL varchar (CHARACTER VARYING) type.
 *
 * <p>Its wire is the charset text in both formats, so it shares {@link AbstractTextCodec}'s logic under a
 * different type name.</p>
 */
public final class VarcharCodec extends AbstractTextCodec {

  public static final VarcharCodec INSTANCE = new VarcharCodec();

  private VarcharCodec() {
    super("varchar");
  }
}
