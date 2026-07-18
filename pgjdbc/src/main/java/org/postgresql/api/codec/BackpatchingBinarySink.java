/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import java.io.IOException;

/**
 * Append-only binary sink with back-patch support, the target a
 * {@link StreamingBinaryCodec} writes into.
 *
 * <p>Bytes are appended in call order: each {@code write*} advances the write
 * position by the number of bytes it emits. Multi-byte integers and floats are
 * written in big-endian (PostgreSQL wire / network) byte order.</p>
 *
 * <h2>Back-patching a length prefix</h2>
 *
 * <p>A container that must prefix a nested value with its byte length does not
 * know that length up front. Instead of buffering the nested value into a
 * temporary {@code byte[]}, it reserves the slot, streams the value straight
 * into this sink, then patches the slot once the length is known:</p>
 *
 * <pre>{@code
 * int slot = out.reserveInt32();   // reserve a 4-byte length placeholder
 * int start = out.position();      // remember where the body starts
 * codec.encodeBinary(value, type, ctx, out);
 * out.setInt32At(slot, out.position() - start);  // patch with the body length
 * }</pre>
 *
 * <p>{@link #reserveInt32()} returns the position of the reserved slot;
 * {@link #setInt32At(int, int)} overwrites four bytes at a previously reserved
 * (or already written) position without moving the write position;
 * {@link #position()} is the current write offset. These three operate on
 * already-buffered bytes, so they never perform I/O.</p>
 *
 * <h2>Lifetime</h2>
 *
 * <p>The sink is owned by the calling container. A codec must write its value
 * and return; it must not retain a reference to the sink beyond the call.</p>
 *
 * @since 42.8.0
 */
@Experimental("Streaming codec API is experimental and may change in future releases")
public interface BackpatchingBinarySink {

  /**
   * @return the current write position (the number of bytes written so far).
   */
  int position();

  /**
   * Appends a single byte, taking the low 8 bits of {@code b} ({@code b & 0xFF})
   * and ignoring the higher bits, matching {@link java.io.OutputStream#write(int)}.
   *
   * @param b the byte to append, in its low 8 bits
   * @throws IOException if the underlying sink throws
   */
  void writeByte(int b) throws IOException;

  /**
   * Appends all bytes of {@code bytes} at the current position.
   *
   * @param bytes the bytes to append
   * @throws IOException if the underlying sink throws
   */
  void write(byte[] bytes) throws IOException;

  /**
   * Appends {@code length} bytes from {@code bytes} starting at {@code offset}.
   *
   * @param bytes the source buffer
   * @param offset start offset within {@code bytes}
   * @param length number of bytes to append
   * @throws IOException if the underlying sink throws
   */
  void write(byte[] bytes, int offset, int length) throws IOException;

  /**
   * Appends a big-endian 2-byte signed integer (the low 16 bits of {@code value}).
   *
   * @param value the {@code int16} to append
   * @throws IOException if the underlying sink throws
   */
  void writeInt16(int value) throws IOException;

  /**
   * Appends a big-endian 4-byte signed integer at the current position.
   *
   * @param value the {@code int32} to append
   * @throws IOException if the underlying sink throws
   */
  void writeInt32(int value) throws IOException;

  /**
   * Appends a big-endian 8-byte signed integer.
   *
   * @param value the {@code int64} to append
   * @throws IOException if the underlying sink throws
   */
  void writeInt64(long value) throws IOException;

  /**
   * Appends a big-endian IEEE-754 4-byte float (via {@link Float#floatToIntBits}).
   *
   * @param value the {@code float4} to append
   * @throws IOException if the underlying sink throws
   */
  void writeFloat(float value) throws IOException;

  /**
   * Appends a big-endian IEEE-754 8-byte double (via {@link Double#doubleToLongBits}).
   *
   * @param value the {@code float8} to append
   * @throws IOException if the underlying sink throws
   */
  void writeDouble(double value) throws IOException;

  /**
   * Reserves a 4-byte signed integer slot at the current position and returns
   * the slot's position for a later {@link #setInt32At(int, int)}.
   *
   * @return the position of the reserved slot
   */
  int reserveInt32();

  /**
   * Overwrites the 4-byte signed integer slot at {@code position} with
   * {@code value}, without changing the current write position. The caller must
   * have previously reserved or written at least 4 bytes at {@code position}.
   *
   * @param position the slot position, as returned by {@link #reserveInt32()}
   * @param value the {@code int32} to patch in
   */
  void setInt32At(int position, int value);
}
