/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.io.Serializable;
import java.sql.SQLException;

/**
 * This implements a class that handles the PostgreSQL int8range type
 */
public final class PGint8range extends PGrange<Long> implements PGBinaryObject, Serializable, Cloneable {

  /**
   * Initialize a range with a given bounds.
   *
   * @param lowerBound the lower bound, {@code null} if not set
   * @param lowerInclusive {@code true} to make the lower bound is inclusive,
   *                       {@code false} to make the lower bound is exclusive
   * @param upperBound the upper bound, {@code null} if not set
   * @param upperInclusive {@code true} to make the upper bound is inclusive,
   *                       {@code false} to make the upper bound is exclusive
   */
  public PGint8range(Long lowerBound, boolean lowerInclusive,
          Long upperBound, boolean upperInclusive) {
    super(lowerBound, lowerInclusive, upperBound, upperInclusive);
    setType("int8range");
  }

  /**
   * Initialize a range with a given bounds.
   *
   * @param lowerBound the lower bound, inclusive, {@code null} if not set
   * @param upperBound the upper bound, exclusive, {@code null} if not set
   */
  public PGint8range(Long lowerBound, Long upperBound) {
    super(lowerBound, upperBound);
    setType("int8range");
  }

  /**
   * Required constructor, initializes an empty range.
   */
  public PGint8range() {
    setType("int8range");
  }

  /**
   * Initialize a range with a given range string representation.
   *
   * @param value String represented range (e.g. '[1,3)')
   * @throws SQLException Is thrown if the string representation has an unknown format
   * @see #setValue(String)
   */
  public PGint8range(String value) throws SQLException {
    super(value);
    setType("int8range");
  }

  @Override
  protected String serializeBound(Long value) {
    return value.toString();
  }

  @Override
  protected Long parseToken(String token) {
    return Long.parseLong(token);
  }

  @Override
  public void setByteValue(byte[] value, int offset) throws SQLException {
    TypeTag tag = TypeTag.valueOf(value[offset]);
    switch (tag) {
      case CLOSED: {
        this.lowerInclusive = true;
        this.upperInclusive = false;

        this.lowerBound = readLengthTaggedValue(value, offset + 1);
        this.upperBound = readLengthTaggedValue(value, offset + 13);

        break;
      }
      case LOWER_OPEN: {
        this.lowerInclusive = false;
        this.upperInclusive = false;
        this.lowerBound = null;

        this.upperBound = readLengthTaggedValue(value, offset + 1);

        break;
      }
      case UPPER_OPEN: {
        this.lowerInclusive = true;
        this.upperInclusive = false;
        this.upperBound = null;

        this.lowerBound = readLengthTaggedValue(value, offset + 1);

        break;
      }
      case EMPTY:
      case BOTH_OPEN: {
        this.lowerInclusive = false;
        this.upperInclusive = false;
        this.lowerBound = null;
        this.upperBound = null;

        break;
      }

      default:
        throw new PSQLException(
                GT.tr("Conversion to type {0} failed: {1}.", type, value),
                PSQLState.DATA_TYPE_MISMATCH);
    }
  }

  private Long readLengthTaggedValue(byte[] value, int offset) throws PSQLException {
    int length = ByteConverter.int4(value, offset);
    if (length != 8) {
      throw new PSQLException(
              GT.tr("Conversion to type {0} failed: {1}.", type, value),
              PSQLState.DATA_TYPE_MISMATCH);
    }
    return ByteConverter.int8(value, offset + 4);
  }

  @Override
  public int lengthInBytes() {
    int length = 1;
    if (this.isEmpty()) {
      return length;
    }
    if (!this.isLowerInfinite()) {
      length += 4 + 8;
    }
    if (!this.isUpperInfinite()) {
      length += 4 + 8;
    }
    return length;
  }

  @Override
  public void toBytes(byte[] bytes, int offset) {
    TypeTag tag = this.getTag();
    bytes[offset] = (byte) tag.getValue();
    if (this.isEmpty()) {
      return;
    }

    int idx = offset + 1;
    if (!this.isLowerInfinite()) {
      ByteConverter.int4(bytes, idx, 8);
      idx += 4;
      // normalize to [)
      ByteConverter.int8(bytes, idx, this.lowerBound + (this.isLowerInclusive() ? 0 : 1));
      idx += 8;
    }
    if (!this.isUpperInfinite()) {
      ByteConverter.int4(bytes, idx, 8);
      idx += 4;
      // normalize to [)
      ByteConverter.int8(bytes, idx, this.upperBound + (this.isUpperInclusive() ? 1 : 0));
      idx += 8;
    }
  }

}
