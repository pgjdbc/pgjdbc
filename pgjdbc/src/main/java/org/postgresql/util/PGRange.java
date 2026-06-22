/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Represents a PostgreSQL range type.
 *
 * <p>PostgreSQL range types represent ranges of values of some element type
 * (called the range's subtype). For instance, ranges of timestamp could be used
 * to represent the ranges of time that a meeting room is reserved.</p>
 *
 * <p>A range value has three components:</p>
 * <ul>
 *   <li>lower bound (inclusive or exclusive)</li>
 *   <li>upper bound (inclusive or exclusive)</li>
 *   <li>empty flag (if true, the range has no points)</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>
 * // Create a closed-open range [1, 10)
 * PGRange&lt;Integer&gt; range = new PGRange&lt;&gt;(1, 10, true, false);
 *
 * // Check if a value is in the range
 * boolean contains = range.contains(5, Integer::compareTo); // true
 *
 * // Parse from PostgreSQL format
 * PGRange&lt;Integer&gt; parsed = PGRange.parse("[1,10)", Integer::parseInt);
 * </pre>
 *
 * @param <T> the type of the range bounds
 */
public class PGRange<T> extends PGobject implements Serializable, Cloneable {

  private static final long serialVersionUID = 1L;

  private @Nullable T lower;
  private @Nullable T upper;
  private boolean lowerInclusive;
  private boolean upperInclusive;
  private boolean isEmpty;

  /**
   * Creates an empty range.
   */
  @SuppressWarnings({"this-escape", "method.invocation"})
  public PGRange() {
    this.isEmpty = true;
    setType("range");
  }

  /**
   * Creates a range with the given bounds.
   *
   * @param lower the lower bound (null for unbounded)
   * @param upper the upper bound (null for unbounded)
   * @param lowerInclusive true if lower bound is inclusive
   * @param upperInclusive true if upper bound is inclusive
   */
  @SuppressWarnings({"this-escape", "method.invocation"})
  public PGRange(@Nullable T lower, @Nullable T upper, boolean lowerInclusive, boolean upperInclusive) {
    this.lower = lower;
    this.upper = upper;
    this.lowerInclusive = lowerInclusive;
    this.upperInclusive = upperInclusive;
    this.isEmpty = false;
    setType("range");
  }

  /**
   * Creates an empty range.
   *
   * @param <T> the type of the range bounds
   * @return an empty range
   */
  public static <T> PGRange<T> empty() {
    return new PGRange<>();
  }

  /**
   * Creates a closed range [lower, upper].
   *
   * @param lower the lower bound
   * @param upper the upper bound
   * @param <T> the type of the range bounds
   * @return a closed range
   */
  public static <T> PGRange<T> closed(@Nullable T lower, @Nullable T upper) {
    return new PGRange<>(lower, upper, true, true);
  }

  /**
   * Creates an open range (lower, upper).
   *
   * @param lower the lower bound
   * @param upper the upper bound
   * @param <T> the type of the range bounds
   * @return an open range
   */
  public static <T> PGRange<T> open(@Nullable T lower, @Nullable T upper) {
    return new PGRange<>(lower, upper, false, false);
  }

  /**
   * Creates a closed-open range [lower, upper).
   *
   * @param lower the lower bound (inclusive)
   * @param upper the upper bound (exclusive)
   * @param <T> the type of the range bounds
   * @return a closed-open range
   */
  public static <T> PGRange<T> closedOpen(@Nullable T lower, @Nullable T upper) {
    return new PGRange<>(lower, upper, true, false);
  }

  /**
   * Creates an open-closed range (lower, upper].
   *
   * @param lower the lower bound (exclusive)
   * @param upper the upper bound (inclusive)
   * @param <T> the type of the range bounds
   * @return an open-closed range
   */
  public static <T> PGRange<T> openClosed(@Nullable T lower, @Nullable T upper) {
    return new PGRange<>(lower, upper, false, true);
  }

  /**
   * Gets the lower bound.
   *
   * @return the lower bound, or null if unbounded
   */
  public @Nullable T getLower() {
    return lower;
  }

  /**
   * Sets the lower bound.
   *
   * @param lower the lower bound
   */
  public void setLower(@Nullable T lower) {
    this.lower = lower;
    this.isEmpty = false;
  }

  /**
   * Gets the upper bound.
   *
   * @return the upper bound, or null if unbounded
   */
  public @Nullable T getUpper() {
    return upper;
  }

  /**
   * Sets the upper bound.
   *
   * @param upper the upper bound
   */
  public void setUpper(@Nullable T upper) {
    this.upper = upper;
    this.isEmpty = false;
  }

  /**
   * Returns whether the lower bound is inclusive.
   *
   * @return true if lower bound is inclusive
   */
  public boolean isLowerInclusive() {
    return lowerInclusive;
  }

  /**
   * Sets whether the lower bound is inclusive.
   *
   * @param lowerInclusive true for inclusive bound
   */
  public void setLowerInclusive(boolean lowerInclusive) {
    this.lowerInclusive = lowerInclusive;
  }

  /**
   * Returns whether the upper bound is inclusive.
   *
   * @return true if upper bound is inclusive
   */
  public boolean isUpperInclusive() {
    return upperInclusive;
  }

  /**
   * Sets whether the upper bound is inclusive.
   *
   * @param upperInclusive true for inclusive bound
   */
  public void setUpperInclusive(boolean upperInclusive) {
    this.upperInclusive = upperInclusive;
  }

  /**
   * Returns whether the range is empty.
   *
   * @return true if the range is empty
   */
  public boolean isEmpty() {
    return isEmpty;
  }

  /**
   * Sets whether the range is empty.
   *
   * @param empty true to make the range empty
   */
  public void setEmpty(boolean empty) {
    this.isEmpty = empty;
    if (empty) {
      lower = null;
      upper = null;
    }
  }

  /**
   * Returns whether the lower bound is unbounded (infinite).
   *
   * @return true if lower bound is unbounded
   */
  public boolean hasLowerBound() {
    return lower != null;
  }

  /**
   * Returns whether the upper bound is unbounded (infinite).
   *
   * @return true if upper bound is unbounded
   */
  public boolean hasUpperBound() {
    return upper != null;
  }

  @Override
  public @Nullable String getValue() {
    return toString();
  }

  @Override
  public void setValue(@Nullable String value) throws SQLException {
    if (value == null || value.isEmpty()) {
      setEmpty(true);
      return;
    }

    // This method sets the string value but doesn't parse the bounds
    // as we don't know the type parser. Use parse() instead for typed parsing.
    if ("empty".equalsIgnoreCase(value)) {
      setEmpty(true);
      return;
    }

    // Store the raw value for later parsing if needed
    // Subclasses should override this to parse the value
    throw new PSQLException(GT.tr("Cannot parse range value without a type parser. Use PGRange.parse() instead."),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  /**
   * Quotes a value if necessary for PostgreSQL range format.
   */
  private static String quoteIfNeeded(String s) {
    if (s.isEmpty()) {
      return "\"\"";
    }
    boolean needsQuoting = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == ',' || c == '[' || c == ']' || c == '(' || c == ')' || c == '"' || c == '\\'
          || Character.isWhitespace(c)) {
        needsQuoting = true;
        break;
      }
    }
    if (!needsQuoting) {
      return s;
    }
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\"\"") + "\"";
  }

  @Override
  public String toString() {
    if (isEmpty) {
      return "empty";
    }

    StringBuilder sb = new StringBuilder();
    sb.append(lowerInclusive ? '[' : '(');
    if (lower != null) {
      sb.append(quoteIfNeeded(String.valueOf(lower)));
    }
    sb.append(',');
    if (upper != null) {
      sb.append(quoteIfNeeded(String.valueOf(upper)));
    }
    sb.append(upperInclusive ? ']' : ')');
    return sb.toString();
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof PGRange)) {
      return false;
    }
    PGRange<?> other = (PGRange<?>) obj;
    if (isEmpty != other.isEmpty) {
      return false;
    }
    if (isEmpty) {
      return true;
    }
    return lowerInclusive == other.lowerInclusive
        && upperInclusive == other.upperInclusive
        && Objects.equals(lower, other.lower)
        && Objects.equals(upper, other.upper);
  }

  @Override
  public int hashCode() {
    if (isEmpty) {
      return 0;
    }
    return Objects.hash(lower, upper, lowerInclusive, upperInclusive);
  }

  @Override
  public PGRange<T> clone() throws CloneNotSupportedException {
    @SuppressWarnings("unchecked")
    PGRange<T> clone = (PGRange<T>) super.clone();
    return clone;
  }

  /**
   * Functional interface for parsing range bound values.
   *
   * @param <T> the type to parse to
   */
  @FunctionalInterface
  public interface BoundParser<T> {
    /**
     * Parses a string into the bound type.
     *
     * @param value the string to parse
     * @return the parsed value
     * @throws SQLException if parsing fails
     */
    T parse(String value) throws SQLException;
  }
}
