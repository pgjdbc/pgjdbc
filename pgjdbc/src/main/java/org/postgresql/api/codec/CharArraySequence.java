/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

/**
 * A mutable {@link CharSequence} view over a {@code [offset, offset + length)} slice of a
 * {@code char[]}, so a container codec can hand one element to a {@link PrimitiveTextDecoder}
 * accessor without copying it out into its own {@code String}.
 *
 * <p>The primitive text accessors ({@link PrimitiveTextDecoder#decodeAsInt} and friends) take a
 * {@link CharSequence}: a caller that already holds a {@code String} passes it straight through
 * (a {@code String} is a {@code CharSequence}), and a caller decoding an element in place off a
 * larger buffer wraps the slice with this class. A single instance can be {@linkplain #reset reset}
 * onto successive elements, so a codec looping over an array literal reuses one view and allocates
 * no {@code String} per element.</p>
 *
 * <p>The backing buffer is borrowed: the view is valid only while the buffer's contents are, so a
 * decoder must parse the value during the call and must not retain the sequence. Instances are
 * mutable and therefore not thread-safe; confine one to a single decode.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public final class CharArraySequence implements CharSequence {

  private static final char[] EMPTY = new char[0];

  private char[] data = EMPTY;
  private int offset;
  private int length;

  /** Creates an empty view; call {@link #reset} before use. */
  public CharArraySequence() {
  }

  /** Creates a view over {@code data[offset, offset + length)}. */
  public CharArraySequence(char[] data, int offset, int length) {
    reset(data, offset, length);
  }

  /**
   * Points this view at the slice {@code data[offset, offset + length)} and returns {@code this}, so
   * a caller can reuse one instance across a decode loop: {@code decoder.decodeAsInt(seq.reset(buf,
   * off, len), type, ctx)}.
   *
   * @param data the backing buffer
   * @param offset start of the slice within {@code data}
   * @param length number of chars in the slice
   * @return this view
   */
  public CharArraySequence reset(char[] data, int offset, int length) {
    if (offset < 0 || length < 0 || offset + length > data.length) {
      throw new IndexOutOfBoundsException(
          "offset=" + offset + ", length=" + length + ", data.length=" + data.length);
    }
    this.data = data;
    this.offset = offset;
    this.length = length;
    return this;
  }

  @Override
  public int length() {
    return length;
  }

  @Override
  public char charAt(int index) {
    if (index < 0 || index >= length) {
      throw new IndexOutOfBoundsException("index=" + index + ", length=" + length);
    }
    return data[offset + index];
  }

  @Override
  public CharArraySequence subSequence(int start, int end) {
    if (start < 0 || end > length || start > end) {
      throw new IndexOutOfBoundsException(
          "start=" + start + ", end=" + end + ", length=" + length);
    }
    // Another view over the same backing buffer, not a copy: the sub-sequence shares this view's
    // borrowed buffer and is valid only for as long as it is.
    return new CharArraySequence(data, offset + start, end - start);
  }

  @Override
  public String toString() {
    return new String(data, offset, length);
  }
}
