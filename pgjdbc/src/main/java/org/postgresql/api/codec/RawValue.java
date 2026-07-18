/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * One PostgreSQL value in its wire form: a {@link Format} plus the bytes that encode the value in
 * that format. {@link Codecs#encode} produces it and {@link Codecs#decode} consumes it. It carries
 * no SQL type — the {@link TypeDescriptor} is supplied separately to the encode and decode calls.
 *
 * <h2>Buffer ownership</h2>
 *
 * <p>A {@code RawValue} is a <em>borrowed view</em> over its backing array: the factory methods do
 * not copy, so the value stays valid only while that array is unchanged. When the backing array is a
 * slice of a larger receive buffer (a {@code COPY} row, a network frame), the view is valid only for
 * the duration of the call it is handed to; keep the bytes beyond that with {@link #toByteArray()}.
 * The array a {@link Codecs#encode} result wraps is freshly allocated and unshared, so that result
 * is safe to retain.</p>
 *
 * <p>A {@code RawValue} always represents a present (non-NULL) value. SQL NULL has no wire bytes and
 * is modelled by the absence of a {@code RawValue}, never by an empty one.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public final class RawValue {

  private final Format format;
  private final byte[] bytes;
  private final int offset;
  private final int length;

  private RawValue(Format format, byte[] bytes, int offset, int length) {
    if (offset < 0 || length < 0 || offset > bytes.length - length) {
      throw new IndexOutOfBoundsException(
          "offset=" + offset + ", length=" + length + ", array length=" + bytes.length);
    }
    this.format = format;
    this.bytes = bytes;
    this.offset = offset;
    this.length = length;
  }

  /**
   * Wraps the whole of {@code bytes} as a value in {@code format}. The array is borrowed, not copied.
   *
   * @param format the wire format of the bytes
   * @param bytes the encoded value
   * @return the wrapped value
   */
  public static RawValue of(Format format, byte[] bytes) {
    return new RawValue(format, bytes, 0, bytes.length);
  }

  /**
   * Wraps {@code bytes[offset, offset + length)} as a value in {@code format}. The array is borrowed,
   * not copied, so the slice must stay unchanged while the value is in use.
   *
   * @param format the wire format of the bytes
   * @param bytes the backing array
   * @param offset start of this value within {@code bytes}
   * @param length number of bytes for this value
   * @return the wrapped value
   */
  public static RawValue of(Format format, byte[] bytes, int offset, int length) {
    return new RawValue(format, bytes, offset, length);
  }

  /**
   * Wraps {@code bytes} as a {@link Format#BINARY} value.
   *
   * @param bytes the binary representation
   * @return the wrapped value
   */
  public static RawValue binary(byte[] bytes) {
    return of(Format.BINARY, bytes);
  }

  /**
   * Wraps {@code bytes} as a {@link Format#TEXT} value. The bytes are the text representation encoded
   * in the connection's character set, the same as the wire carries.
   *
   * @param bytes the text representation, charset-encoded
   * @return the wrapped value
   */
  public static RawValue text(byte[] bytes) {
    return of(Format.TEXT, bytes);
  }

  /**
   * Returns the wire format of this value.
   *
   * @return the format
   */
  public Format getFormat() {
    return format;
  }

  /**
   * Returns the start of this value within the backing array.
   *
   * @return the offset
   */
  public int getOffset() {
    return offset;
  }

  /**
   * Returns the number of bytes this value occupies.
   *
   * @return the length in bytes
   */
  public int getLength() {
    return length;
  }

  /**
   * Returns an independent copy of this value's bytes. Use it to keep the bytes beyond the borrowed
   * view's lifetime.
   *
   * @return a fresh byte array holding exactly this value
   */
  public byte[] toByteArray() {
    return Arrays.copyOfRange(bytes, offset, offset + length);
  }

  /**
   * Decodes this value's bytes as text using {@code charset}. Meaningful for a {@link Format#TEXT}
   * value, whose bytes are the charset-encoded text representation.
   *
   * @param charset the character set the bytes are encoded in
   * @return the decoded text
   */
  public String asString(Charset charset) {
    return new String(bytes, offset, length, charset);
  }

  /**
   * The backing array, for in-place reads by {@link Codecs} without a copy. Package-private: callers
   * outside the codec layer must use {@link #toByteArray()}.
   */
  byte[] backingArray() {
    return bytes;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RawValue)) {
      return false;
    }
    RawValue that = (RawValue) o;
    if (format != that.format || length != that.length) {
      return false;
    }
    for (int i = 0; i < length; i++) {
      if (bytes[offset + i] != that.bytes[that.offset + i]) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    int result = format.hashCode();
    for (int i = 0; i < length; i++) {
      result = result * 31 + bytes[offset + i];
    }
    return result;
  }

  @Override
  public String toString() {
    return "RawValue[" + format + ", " + length + " bytes]";
  }
}
