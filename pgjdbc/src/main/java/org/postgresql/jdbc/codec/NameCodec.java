/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

/**
 * Codec for PostgreSQL name type.
 *
 * <p>name is an internal system type for object names (63 bytes max); its wire is the charset text in both
 * formats, so it shares {@link AbstractTextCodec}'s logic under a different type name.</p>
 */
public final class NameCodec extends AbstractTextCodec {

  public static final NameCodec INSTANCE = new NameCodec();

  private NameCodec() {
    super("name");
  }
}
