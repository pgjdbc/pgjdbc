/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;

/**
 * {@link Appendable} wrapper that emits characters into a delegate, escaping any
 * {@code "} or {@code \\} character in one of the two PostgreSQL container styles.
 *
 * <p>{@code array_out} escapes both characters with a leading backslash
 * ({@code "} → {@code \"}, {@code \\} → {@code \\\\}); {@code record_out} doubles
 * them instead ({@code "} → {@code ""}, {@code \\} → {@code \\\\}). The default
 * constructor keeps the array style; pass {@code recordStyle = true} for composite
 * fields. {@link LiteralCursor} accepts both on decode, so this only matters when
 * the produced text must match the server byte-for-byte.</p>
 *
 * <p>This wrapper does not add the surrounding quotes for an array element or
 * composite field; callers are responsible for writing those quotes. The
 * wrapper only applies one escaping layer. Wrapping an already escaped sink is
 * intentional and represents nested text formats, for example an array element
 * that is a composite whose field is itself an array.</p>
 */
public final class EscapingAppendable implements Appendable {

  private final Appendable delegate;
  private final boolean recordStyle;

  public EscapingAppendable(Appendable delegate) {
    this(delegate, false);
  }

  public EscapingAppendable(Appendable delegate, boolean recordStyle) {
    this.delegate = delegate;
    this.recordStyle = recordStyle;
  }

  @Override
  public Appendable append(@Nullable CharSequence csq) throws IOException {
    if (csq == null) {
      return append("null");
    }
    return append(csq, 0, csq.length());
  }

  @Override
  public Appendable append(@Nullable CharSequence csq, int start, int end) throws IOException {
    if (csq == null) {
      csq = "null";
    }
    for (int i = start; i < end; i++) {
      append(csq.charAt(i));
    }
    return this;
  }

  @Override
  public Appendable append(char c) throws IOException {
    if (c == '"' || c == '\\') {
      // record_out doubles the character; array_out prefixes a backslash.
      delegate.append(recordStyle ? c : '\\');
    }
    delegate.append(c);
    return this;
  }
}
