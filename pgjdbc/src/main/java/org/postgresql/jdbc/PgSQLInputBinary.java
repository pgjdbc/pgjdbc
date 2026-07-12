/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.PrimitiveDecoders;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.util.ByteConverter;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Arrays;

/**
 * Binary format SQLInput implementation.
 *
 * <p>Reads binary-encoded composite data with a forward cursor over the wire buffer rather than
 * materialising every field up front: each {@code readXxx} advances past one field's
 * {@code oid}/{@code length} header and decodes its body in place. Primitive reads
 * ({@code readInt}, {@code readLong}, ...) go through the slice-form {@link PrimitiveDecoders} and
 * never allocate; only the readers whose codec method has no slice form (String, BigDecimal, bytes,
 * temporal, array, and the type-mapped object path) copy the field's bytes out, and only for the
 * field actually read. This mirrors {@link PgSQLOutputBinary}, which streams into a sink.</p>
 *
 * <p>The cursor is bounded by the slice it was handed ({@code [offset, offset + length)}): every
 * header and body read is checked against that end, so a truncated or sub-sliced buffer fails with a
 * clear {@code DATA_ERROR} instead of reading neighbouring bytes. A field's codec is resolved once, in
 * {@link #advanceIsNull()}, the moment the cursor reaches it - and only if it turns out non-null, since
 * a null field is never decoded.</p>
 */
public final class PgSQLInputBinary extends PgSQLInput {

  /**
   * The codec for the current field (the one just entered by {@link #advanceIsNull()}). Valid only
   * right after a non-null {@link #advanceIsNull()}.
   */
  private @Nullable BinaryCodec currentCodec;

  /** The backing buffer; only {@code [pos, end)} is still to be read. */
  private final byte[] source;

  /** One past the last byte of this composite within {@link #source}. */
  private final int end;

  /** The number of fields the wire header declares. */
  private final int wireFieldCount;

  /** Cursor into {@link #source}: the start of the next field's header. */
  private int pos;

  /** Offset of the current field's body within {@link #source} (valid after {@link #advanceIsNull()}). */
  private int curOffset;

  /** Length of the current field's body, or -1 for SQL NULL. */
  private int curLength = -1;

  /**
   * Creates a new PgSQLInputBinary from raw composite binary data.
   *
   * @param compositeData the raw binary data for the composite type
   * @param type the composite type
   * @param ctx the codec context
   */
  public PgSQLInputBinary(byte[] compositeData, PgType type, PgCodecContext ctx)
      throws SQLException {
    this(compositeData, 0, compositeData.length, type, ctx);
  }

  /**
   * Creates a new PgSQLInputBinary over the composite that occupies {@code source[offset, offset +
   * length)}. The slice bounds the cursor, so a nested composite can be read straight off its parent's
   * buffer without copying it out first.
   *
   * @param source the backing buffer
   * @param offset start of the composite within {@code source}
   * @param length length of the composite in bytes
   * @param type the composite type
   * @param ctx the codec context
   */
  public PgSQLInputBinary(byte[] source, int offset, int length, PgType type, PgCodecContext ctx)
      throws SQLException {
    super(type, ctx);
    if (length < 4) {
      throw Exceptions.invalidCompositeTooShort();
    }
    this.source = source;
    this.end = offset + length;
    int count = ByteConverter.int4(source, offset);
    if (count < 0) {
      throw Exceptions.invalidCompositeNegativeFieldCount(count);
    }
    this.wireFieldCount = count;
    this.pos = offset + 4;
  }

  @Override
  protected boolean advanceIsNull() throws SQLException {
    // Fewer wire fields than the type declares: the trailing attributes read back as NULL, matching
    // the previous array-based reader (which returned null past the parsed attribute count).
    if (fieldIndex - 1 >= wireFieldCount) {
      curLength = -1;
      return true;
    }
    // Field header: int4 oid, int4 length. Bound every read against `end` so a truncated or sub-sliced
    // buffer fails cleanly instead of reading past the composite into neighbouring bytes.
    if (end - pos < 8) {
      throw Exceptions.invalidCompositeUnexpectedEnd(fieldIndex - 1);
    }
    // The declared field type drives decoding, so the wire OID is skipped.
    pos += 4;
    int length = ByteConverter.int4(source, pos);
    pos += 4;
    if (length == -1) {
      curLength = -1;
      return true;
    }
    if (length < 0) {
      throw Exceptions.invalidCompositeFieldLength(length, fieldIndex - 1);
    }
    if (end - pos < length) {
      throw Exceptions.invalidCompositeNotEnoughData(fieldIndex - 1);
    }
    curOffset = pos;
    curLength = length;
    pos += length;
    currentCodec = castNonNull(ctx.resolveBinaryCodec(fields.get(fieldIndex - 1).getTypeOid()));
    return false;
  }

  /**
   * Gets the codec for the current field (the one just entered by advanceIsNull()).
   */
  private BinaryCodec getCodec() {
    return castNonNull(currentCodec);
  }

  @Override
  protected int decodeInt() throws SQLException {
    return PrimitiveDecoders.asInt(getCodec(), source, curOffset, curLength, getCurrentType(), ctx);
  }

  @Override
  protected long decodeLong() throws SQLException {
    return PrimitiveDecoders.asLong(getCodec(), source, curOffset, curLength, getCurrentType(), ctx);
  }

  @Override
  protected double decodeDouble() throws SQLException {
    return PrimitiveDecoders.asDouble(getCodec(), source, curOffset, curLength, getCurrentType(), ctx);
  }

  @Override
  protected float decodeFloat() throws SQLException {
    return (float) PrimitiveDecoders.asDouble(getCodec(), source, curOffset, curLength, getCurrentType(), ctx);
  }

  @Override
  protected boolean decodeBoolean() throws SQLException {
    return PrimitiveDecoders.asBoolean(getCodec(), source, curOffset, curLength, getCurrentType(), ctx);
  }

  @Override
  protected @Nullable String decodeString() throws SQLException {
    return getCodec().decodeAsString(source, curOffset, curLength, getCurrentType(), ctx);
  }

  @Override
  protected @Nullable BigDecimal decodeBigDecimal() throws SQLException {
    return PrimitiveDecoders.asBigDecimal(getCodec(), source, curOffset, curLength, getCurrentType(), ctx);
  }

  @Override
  protected byte @Nullable [] decodeBytes() throws SQLException {
    return getCodec().decodeAsBytes(source, curOffset, curLength, getCurrentType(), ctx);
  }

  @Override
  protected @Nullable Date decodeDate() throws SQLException {
    return getCodec().decodeBinaryAs(source, curOffset, curLength, getCurrentType(), Date.class, ctx);
  }

  @Override
  protected @Nullable Time decodeTime() throws SQLException {
    return getCodec().decodeBinaryAs(source, curOffset, curLength, getCurrentType(), Time.class, ctx);
  }

  @Override
  protected @Nullable Timestamp decodeTimestamp() throws SQLException {
    return getCodec().decodeBinaryAs(source, curOffset, curLength, getCurrentType(), Timestamp.class, ctx);
  }

  @Override
  protected @Nullable Object decodeObject() throws SQLException {
    // Honor only the explicit JDBC typeMap here. If no explicit mapping is
    // present, return the codec's default Java type so SPI-provided codecs can
    // surface their own Java objects instead of being forced through the
    // legacy PGobject registry.
    TypeDescriptor currentType = getCurrentType();
    Class<?> mapped = ctx.getTypeMap().get(currentType.getFullName());
    if (mapped == null) {
      mapped = ctx.getTypeMap().get(currentType.getTypeName().getName());
    }
    if (mapped != null) {
      return getCodec().decodeBinaryAs(source, curOffset, curLength, currentType, mapped, ctx);
    }
    return getCodec().decodeBinary(source, curOffset, curLength, currentType, ctx);
  }

  @Override
  @SuppressWarnings("unchecked")
  protected <T> @Nullable T decodeObjectAs(Class<T> type) throws SQLException {
    return getCodec().decodeBinaryAs(source, curOffset, curLength, getCurrentType(), type, ctx);
  }

  @Override
  protected Array decodeArray() throws SQLException {
    // A nested array materializes a connection-bound PgArray; offline reports a clear limitation. The
    // field bytes must be copied out because the PgArray outlives this reader and owns its own buffer.
    byte[] arrayBytes = Arrays.copyOfRange(source, curOffset, curOffset + curLength);
    return new PgArray(ctx.requireConnection(getCurrentType()), getCurrentType().getOid(), arrayBytes);
  }
}
