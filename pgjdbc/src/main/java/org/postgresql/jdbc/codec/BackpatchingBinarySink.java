/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Binary sink that can append bytes and back-patch previously reserved slots.
 *
 * <p>This is the internal composition contract for binary codecs that need to
 * write length-prefixed nested values without allocating a temporary
 * {@code byte[]} for every nested value.</p>
 */
interface BackpatchingBinarySink {

  /**
   * @return this sink as an {@link OutputStream} for nested streaming codecs.
   */
  OutputStream asOutputStream();

  /**
   * @return current write position.
   */
  int position();

  /**
   * Writes the bytes at the current position.
   */
  void write(byte[] bytes) throws IOException;

  /**
   * Writes {@code length} bytes from {@code bytes} starting at {@code offset}.
   */
  void write(byte[] bytes, int offset, int length) throws IOException;

  /**
   * Reserves a 4-byte signed integer slot and returns its position.
   */
  int reserveInt32();

  /**
   * Patches a previously reserved 4-byte signed integer slot.
   */
  void setInt32At(int position, int value);

  /**
   * Writes a 4-byte signed integer at the current position.
   */
  void writeInt32(int value) throws IOException;
}
