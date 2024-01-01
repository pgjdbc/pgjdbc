/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Abstract base class for all range types.
 *
 * @param <T> the type of element in the range
 */
public abstract class PGrange<T extends Serializable> extends PGobject implements Serializable, Cloneable {

  /**
   * Type tag for the a range over the binary protocol.
   */
  enum TypeTag {

    /**
     * Both the lower and the upper bound are set.
     */
    EMPTY(1),

    /**
     * Both the lower and the upper bound are set.
     */
    CLOSED(2),

    /**
     * The lower bound is not set but the upper bound is set.
     */
    LOWER_OPEN(8),

    /**
     * The lower bound is set but the upper bound is not set.
     */
    UPPER_OPEN(18),

    /**
     * Neither the lower nor the upper bound are set.
     */
    BOTH_OPEN(24);

    TypeTag(int value) {
      this.value = value;
    }

    static final Map<Integer, TypeTag> VALUE_TO_INSTANCE;

    static {
      VALUE_TO_INSTANCE = new HashMap<Integer, TypeTag>();
      for (TypeTag each : values()) {
        VALUE_TO_INSTANCE.put(each.getValue(), each);
      }
    }

    private final int value;

    int getValue() {
      return this.value;
    }

    static TypeTag valueOf(int i) throws SQLException {
      TypeTag typeTag = VALUE_TO_INSTANCE.get(i);
      if (typeTag == null) {
        throw new PSQLException(
            GT.tr("Unknown type tag: {0}.", i),
            PSQLState.WRONG_OBJECT_TYPE);
      }
      return typeTag;
    }

  }

  protected boolean lowerInclusive;
  protected boolean upperInclusive;
  protected @Nullable T lowerBound;
  protected @Nullable T upperBound;

  PGrange(@Nullable T lowerBound, boolean lowerInclusive, @Nullable T upperBound,
      boolean upperInclusive) {
    this.lowerBound = lowerBound;
    this.lowerInclusive = lowerInclusive;
    this.upperBound = upperBound;
    this.upperInclusive = upperInclusive;
  }

  PGrange(@Nullable T lowerBound, @Nullable T upperBound) {
    this(lowerBound, true, upperBound, false);
  }

  PGrange() {
    this(null, null);
  }

  /**
   * Initialize a range with a given range string representation.
   *
   * @param value String represented range (e.g. '[1,3)')
   * @throws SQLException Is thrown if the string representation has an unknown format
   * @see #setValue(String)
   */
  @SuppressWarnings("method.invocation.invalid")
  PGrange(@Nullable String value) throws SQLException {
    setValue(value);
  }

  /**
   * Checks if the lower bound is inclusive.
   *
   * @return {@code true} if the lower bound is inclusive,
   *         {@code false} if the lower bound is exclusive
   */
  public boolean isLowerInclusive() {
    return this.lowerInclusive;
  }

  /**
   * Sets the lower bound inclusive.
   *
   * @param lowerInclusive {@code true} to make the lower bound is inclusive,
   *                       {@code false} to make the lower bound is exclusive
   */
  public void setLowerInclusive(boolean lowerInclusive) {
    this.lowerInclusive = lowerInclusive;
  }

  /**
   * Checks if the upper bound is inclusive.
   *
   * @return {@code true} if the upper bound is inclusive,
   *         {@code false} if the upper bound is exclusive
   */
  public boolean isUpperInclusive() {
    return this.upperInclusive;
  }

  /**
   * Sets the upper bound inclusive.
   *
   * @param upperInclusive {@code true} to make the upper bound is inclusive,
   *                       {@code false} to make the upper bound is exclusive
   */
  public void setUpperInclusive(boolean upperInclusive) {
    this.upperInclusive = upperInclusive;
  }

  /**
   * Return the lower bound.
   *
   * @return the lower bound, {@code null} if the lower bound is infinite
   * @see #isLowerInfinite()
   */
  public @Nullable T getLowerBound() {
    return this.lowerBound;
  }

  /**
   * Sets the lower bound.
   *
   * @param lowerBound the lower bound to set,
   *                   {@code null} to set the lower bound to infinite
   */
  public void setLowerBound(@Nullable T lowerBound) {
    this.lowerBound = lowerBound;
  }

  /**
   * Return the upper bound.
   *
   * @return the upper bound, {@code null} if the upper bound is infinite
   * @see #isUpperInfinite()
   */
  public @Nullable T getUpperBound() {
    return this.upperBound;
  }

  /**
   * Sets the upper bound.
   *
   * @param upperBound the upper bound to set,
   *                   {@code null} to set the upper bound to infinite
   */
  public void setUpperBound(@Nullable T upperBound) {
    this.upperBound = upperBound;
  }

  /**
   * Checks if the lower bound is infinite.
   *
   * @return {@code true} if the lower bound is infinite,
   *         {@code false} if the lower bound is set
   */
  public boolean isLowerInfinite() {
    return this.lowerBound == null;
  }

  /**
   * Checks if the upper bound is infinite.
   *
   * @return {@code true} if the upper bound is infinite,
   *         {@code false} if the upper bound is set
   */
  public boolean isUpperInfinite() {
    return this.upperBound == null;
  }

  @Override
  public String getValue() {
    return (this.lowerInclusive ? '[' : '(')
            + (this.lowerBound == null ? "" : this.serializeBound(this.lowerBound))
            + ','
            + (this.upperBound == null ? "" : this.serializeBound(this.upperBound))
            + (this.upperInclusive ? ']' : ')');
  }

  /**
   * Returns a string representation in the text protocol of a value in the range.
   *
   * @param value the value in the range, not {@code null}
   * @return the string representation of {@code value}
   */
  protected abstract String serializeBound(T value);

  @Override
  public void setValue(@Nullable String value) throws SQLException {
    super.setValue(value);
    if (value == null || "empty".equals(value)) {
      this.lowerInclusive = false;
      this.upperInclusive = false;
      this.lowerBound = null;
      this.upperBound = null;
      return;
    }
    if (value.length() < 2) {
      throw new PSQLException(
          GT.tr("Conversion to type {0} failed: {1}.", type, value),
          PSQLState.DATA_TYPE_MISMATCH);
    }
    if (value.charAt(0) == '[') {
      this.lowerInclusive = true;
    } else if (value.charAt(0) == '(') {
      this.lowerInclusive = false;
    } else {
      throw new PSQLException(
          GT.tr("Conversion to type {0} failed: {1}.", type, value),
          PSQLState.DATA_TYPE_MISMATCH);
    }

    if (value.charAt(value.length() - 1) == ']') {
      this.upperInclusive = true;
    } else if (value.charAt(value.length() - 1) == ')') {
      this.upperInclusive = false;
    } else {
      throw new PSQLException(
          GT.tr("Conversion to type {0} failed: {1}.", type, value),
          PSQLState.DATA_TYPE_MISMATCH);
    }

    PGtokenizer t = new PGtokenizer(value.substring(1, value.length() - 1), ',');
    if (t.getSize() == 0 || t.getSize() > 2) {
      throw new PSQLException(
          GT.tr("Conversion to type {0} failed: {1}.", type, value),
          PSQLState.DATA_TYPE_MISMATCH);
    }

    try {
      String token = t.getToken(0);
      this.lowerBound = parseTokenOrEmpty(token);
      if (t.getSize() > 1) {
        token = t.getToken(1);
        this.upperBound = parseTokenOrEmpty(token);
      } else {
        this.upperBound = null;
      }
    } catch (RuntimeException e) {
      throw new PSQLException(
          GT.tr("Conversion to type {0} failed: {1}.", type, value),
          PSQLState.DATA_TYPE_MISMATCH);
    }
  }

  private @Nullable T parseTokenOrEmpty(String token) {
    if (token.isEmpty()) {
      return null;
    } else {
      return parseToken(token);
    }

  }

  protected abstract T parseToken(String token);

  /**
   * Returns the range type tag used at the start of the binary representation.
   *
   * @return the range type tag
   */
  protected TypeTag getTag() {
    if (this.isEmpty()) {
      return TypeTag.EMPTY;
    }
    if (this.isLowerInfinite()) {
      return this.isUpperInfinite() ? TypeTag.BOTH_OPEN : TypeTag.LOWER_OPEN;
    } else {
      return this.isUpperInfinite() ? TypeTag.UPPER_OPEN : TypeTag.CLOSED;
    }
  }

  boolean isEmpty() {
    return nullSafeEquals(this.upperBound, this.lowerBound)
            && (!this.lowerInclusive || !this.upperInclusive);
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (obj == this) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (obj.getClass() != this.getClass()) {
      return false;
    }
    PGrange<T> other = (PGrange<T>) obj;
    return this.lowerInclusive == other.lowerInclusive
            && this.upperInclusive == other.upperInclusive
            && nullSafeEquals(this.lowerBound, other.lowerBound)
            && nullSafeEquals(this.upperBound, other.upperBound);
  }

  protected boolean nullSafeEquals(@Nullable T o1, @Nullable T o2) {
    return Objects.equals(o1, o2);
  }

  @Override
  public int hashCode() {
    int result = 17;
    result = 31 * result + (this.lowerInclusive ? 1 : 0);
    result = 31 * result + (this.upperInclusive ? 1 : 0);
    result = 31 * result + Objects.hashCode(this.lowerBound);
    result = 31 * result + Objects.hashCode(this.upperBound);
    return result;
  }

}
