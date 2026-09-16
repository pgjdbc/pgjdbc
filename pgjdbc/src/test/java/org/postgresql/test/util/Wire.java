/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import java.io.ByteArrayOutputStream;

/**
 * Builds backend bytes in network byte order.
 */
public final class Wire {
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  public Wire int1(int value) {
    out.write(value);
    return this;
  }

  public Wire int2(int value) {
    out.write(value >>> 8);
    out.write(value);
    return this;
  }

  public Wire int4(int value) {
    out.write(value >>> 24);
    out.write(value >>> 16);
    out.write(value >>> 8);
    out.write(value);
    return this;
  }

  /** Appends {@code count} bytes {@code 'a'}, {@code 'b'}, ... */
  public Wire bytes(int count) {
    for (int i = 0; i < count; i++) {
      out.write('a' + i % 26);
    }
    return this;
  }

  public Wire raw(byte[] data) {
    out.write(data, 0, data.length);
    return this;
  }

  public byte[] toBytes() {
    return out.toByteArray();
  }
}
