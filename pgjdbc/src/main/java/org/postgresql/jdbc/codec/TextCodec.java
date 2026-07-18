/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

/**
 * Codec for PostgreSQL text type.
 *
 * <p>Its wire is the charset text in both formats, so it is {@link AbstractTextCodec} under the {@code text}
 * type name. Like its {@code varchar}/{@code bpchar}/{@code name} siblings it is a leaf that never streams:
 * a {@code String} value must be materialised into charset bytes before it can be written either way, so a
 * streaming encoder would save nothing over the {@code byte[]}/{@code String} form (unlike a fixed-width
 * primitive such as {@code int4}, which writes straight into the sink). As a container element it encodes
 * through the non-streaming path, the same as its siblings.</p>
 */
public final class TextCodec extends AbstractTextCodec {

  public static final TextCodec INSTANCE = new TextCodec();

  private TextCodec() {
    super("text");
  }
}
