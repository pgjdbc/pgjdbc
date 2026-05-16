/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.util.ByteConverter;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * {@link ByteArrayOutputStream} subclass that exposes the
 * {@link BackpatchingBinarySink} capability.
 *
 * <p>Used by the streaming binary codec path to write a length placeholder,
 * stream the element body straight into the same buffer, and then back-patch
 * the placeholder with the actual element length once known — without
 * needing a per-element temporary {@code byte[]}.</p>
 */
public final class BackpatchByteArrayOutputStream extends ByteArrayOutputStream
    implements BackpatchingBinarySink {

  public BackpatchByteArrayOutputStream() {
    super();
  }

  public BackpatchByteArrayOutputStream(int initialCapacity) {
    super(initialCapacity);
  }

  /**
   * @return current write position (== size of data written so far).
   */
  @Override
  public ByteArrayOutputStream asOutputStream() {
    return this;
  }

  @Override
  public int position() {
    return count;
  }

  /**
   * Reserves a 4-byte slot at the current position by writing four zero bytes
   * and returns the index of the slot, which can later be passed to
   * {@link #setInt32At(int, int)}.
   */
  @Override
  public int reserveInt32() {
    int pos = count;
    ensureCapacity(count + 4);
    count += 4;
    return pos;
  }

  /**
   * Overwrites the {@code int32} slot at the given position with {@code value}.
   * Caller must have previously written or reserved at least 4 bytes at that
   * position.
   */
  @Override
  public void setInt32At(int position, int value) {
    if (position < 0 || position + 4 > count) {
      throw new IndexOutOfBoundsException(
          "int32 patch position " + position + " is out of bounds (count=" + count + ")");
    }
    ByteConverter.int4(buf, position, value);
  }

  @Override
  public void writeInt32(int value) {
    int pos = reserveInt32();
    ByteConverter.int4(buf, pos, value);
  }

  private void ensureCapacity(int minCapacity) {
    if (minCapacity <= buf.length) {
      return;
    }
    int newCapacity = Math.max(buf.length << 1, minCapacity);
    if (newCapacity < 0) {
      newCapacity = Integer.MAX_VALUE;
    }
    buf = Arrays.copyOf(buf, newCapacity);
  }
}
