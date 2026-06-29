/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Represents a PostgreSQL multirange type.
 *
 * <p>A multirange is an ordered list of non-overlapping, non-empty {@link PGRange} values of one
 * subtype, available since PostgreSQL 14. Every range type has a companion multirange type — for
 * instance {@code int4multirange} over {@code int4range}.</p>
 *
 * <p>The text form is the ranges joined by commas inside braces: {@code {}} for an empty multirange,
 * {@code {[1,5)}} for one range, {@code {[1,5),[10,20)}} for several. The server normalises the
 * ranges (sorting them, merging adjacent ones, and dropping empty ones), so the value read back may
 * differ from the one sent.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * PGmultirange&lt;Integer&gt; mr = new PGmultirange&lt;&gt;(
 *     new PGRange&lt;&gt;(1, 5, true, false),
 *     new PGRange&lt;&gt;(10, 20, true, false));
 * </pre>
 *
 * @param <T> the type of the range bounds
 */
public class PGmultirange<T> extends PGobject implements Serializable, Cloneable {

  private static final long serialVersionUID = 1L;

  private List<PGRange<T>> ranges;

  /**
   * Creates an empty multirange.
   */
  @SuppressWarnings({"this-escape", "method.invocation"})
  public PGmultirange() {
    this.ranges = new ArrayList<>();
    setType("multirange");
  }

  /**
   * Creates a multirange holding the given ranges.
   *
   * @param ranges the ranges, in any order; the server normalises them on a round trip
   */
  @SafeVarargs
  @SuppressWarnings({"this-escape", "method.invocation", "varargs"})
  public PGmultirange(PGRange<T>... ranges) {
    this.ranges = new ArrayList<>(Arrays.asList(ranges));
    setType("multirange");
  }

  /**
   * Creates a multirange holding the given ranges.
   *
   * @param ranges the ranges, in any order; the server normalises them on a round trip
   */
  @SuppressWarnings({"this-escape", "method.invocation"})
  public PGmultirange(List<PGRange<T>> ranges) {
    this.ranges = new ArrayList<>(ranges);
    setType("multirange");
  }

  /**
   * Returns the ranges in this multirange.
   *
   * @return the live list of ranges
   */
  public List<PGRange<T>> getRanges() {
    return ranges;
  }

  /**
   * Replaces the ranges in this multirange.
   *
   * @param ranges the new ranges
   */
  public void setRanges(List<PGRange<T>> ranges) {
    this.ranges = new ArrayList<>(ranges);
  }

  @Override
  public @Nullable String getValue() {
    return toString();
  }

  @Override
  public void setValue(@Nullable String value) throws SQLException {
    // Like PGRange, this does not parse the bounds: it has no subtype parser. Empty forms are
    // recognised so a round trip through PGobject keeps working; a populated literal must go
    // through the codec API, which resolves the subtype.
    if (value == null) {
      this.ranges = new ArrayList<>();
      return;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty() || "{}".equals(trimmed)) {
      this.ranges = new ArrayList<>();
      return;
    }
    throw new PSQLException(
        GT.tr("Cannot parse a populated multirange value without a subtype parser. "
            + "Decode multiranges through the codec API instead."),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    for (int i = 0; i < ranges.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(ranges.get(i));
    }
    sb.append('}');
    return sb.toString();
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof PGmultirange)) {
      return false;
    }
    PGmultirange<?> other = (PGmultirange<?>) obj;
    return ranges.equals(other.ranges);
  }

  @Override
  public int hashCode() {
    return ranges.hashCode();
  }

  @Override
  public PGmultirange<T> clone() throws CloneNotSupportedException {
    @SuppressWarnings("unchecked")
    PGmultirange<T> clone = (PGmultirange<T>) super.clone();
    clone.ranges = new ArrayList<>(this.ranges);
    return clone;
  }
}
