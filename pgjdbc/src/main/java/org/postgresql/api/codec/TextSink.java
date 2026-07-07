/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import java.io.IOException;

/**
 * An {@link Appendable} that also accepts a Java integer as its decimal text without allocating an
 * intermediate {@code String} — the text counterpart of {@link BackpatchingBinarySink}'s typed
 * writers.
 *
 * <p>{@link StringBuilder} already offers {@code append(int)}/{@code append(long)} that write the
 * digits straight into its buffer; a wrapping text sink (such as the composite/array escaping sink)
 * implements this interface so it can forward those to the buffer underneath, and the static
 * {@link #appendInt}/{@link #appendLong} helpers pick that allocation-free path for any
 * {@link Appendable}, falling back to {@link Integer#toString(int)} only when the sink is neither a
 * {@code TextSink} nor a {@link StringBuilder}.</p>
 *
 * <p>An implementation's {@code append(int)}/{@code append(long)} must forward to a
 * <em>different</em> sink (not {@code this}) — for example a wrapper forwards to its delegate — so
 * routing back through {@link #appendInt} cannot recurse forever.</p>
 *
 * @since 42.8.0
 */
@Experimental("Streaming codec API is experimental and may change in future releases")
public interface TextSink extends Appendable {

  /**
   * Appends the decimal text of {@code value}, like {@link StringBuilder#append(int)}, without
   * allocating an intermediate {@code String}.
   *
   * @param value the value to append
   * @return this sink
   * @throws IOException if the underlying sink throws
   */
  TextSink append(int value) throws IOException;

  /**
   * Appends the decimal text of {@code value}, like {@link StringBuilder#append(long)}, without
   * allocating an intermediate {@code String}.
   *
   * @param value the value to append
   * @return this sink
   * @throws IOException if the underlying sink throws
   */
  TextSink append(long value) throws IOException;

  /**
   * Appends the text of {@code value}, like {@link StringBuilder#append(float)}, without allocating
   * an intermediate {@code String}.
   *
   * @param value the value to append
   * @return this sink
   * @throws IOException if the underlying sink throws
   */
  TextSink append(float value) throws IOException;

  /**
   * Appends the text of {@code value}, like {@link StringBuilder#append(double)}, without allocating
   * an intermediate {@code String}.
   *
   * @param value the value to append
   * @return this sink
   * @throws IOException if the underlying sink throws
   */
  TextSink append(double value) throws IOException;

  /**
   * Appends {@code value}'s decimal text to {@code out}, avoiding an intermediate {@code String} when
   * {@code out} is a {@link TextSink} or {@link StringBuilder} and falling back to
   * {@link Integer#toString(int)} otherwise.
   *
   * @param out the sink to append to
   * @param value the value to append
   * @throws IOException if {@code out} throws
   */
  static void appendInt(Appendable out, int value) throws IOException {
    if (out instanceof TextSink) {
      ((TextSink) out).append(value);
    } else if (out instanceof StringBuilder) {
      ((StringBuilder) out).append(value);
    } else {
      out.append(Integer.toString(value));
    }
  }

  /**
   * Appends {@code value}'s decimal text to {@code out}, avoiding an intermediate {@code String} when
   * {@code out} is a {@link TextSink} or {@link StringBuilder} and falling back to
   * {@link Long#toString(long)} otherwise.
   *
   * @param out the sink to append to
   * @param value the value to append
   * @throws IOException if {@code out} throws
   */
  static void appendLong(Appendable out, long value) throws IOException {
    if (out instanceof TextSink) {
      ((TextSink) out).append(value);
    } else if (out instanceof StringBuilder) {
      ((StringBuilder) out).append(value);
    } else {
      out.append(Long.toString(value));
    }
  }

  /**
   * Appends {@code value}'s text to {@code out}, avoiding an intermediate {@code String} when
   * {@code out} is a {@link TextSink} or {@link StringBuilder} and falling back to
   * {@link Float#toString(float)} otherwise.
   *
   * @param out the sink to append to
   * @param value the value to append
   * @throws IOException if {@code out} throws
   */
  static void appendFloat(Appendable out, float value) throws IOException {
    if (out instanceof TextSink) {
      ((TextSink) out).append(value);
    } else if (out instanceof StringBuilder) {
      ((StringBuilder) out).append(value);
    } else {
      out.append(Float.toString(value));
    }
  }

  /**
   * Appends {@code value}'s text to {@code out}, avoiding an intermediate {@code String} when
   * {@code out} is a {@link TextSink} or {@link StringBuilder} and falling back to
   * {@link Double#toString(double)} otherwise.
   *
   * @param out the sink to append to
   * @param value the value to append
   * @throws IOException if {@code out} throws
   */
  static void appendDouble(Appendable out, double value) throws IOException {
    if (out instanceof TextSink) {
      ((TextSink) out).append(value);
    } else if (out instanceof StringBuilder) {
      ((StringBuilder) out).append(value);
    } else {
      out.append(Double.toString(value));
    }
  }
}
