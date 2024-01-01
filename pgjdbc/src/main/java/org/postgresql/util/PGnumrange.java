/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * This numrange a class that handles the PostgreSQL numrange type
 */
public final class PGnumrange extends PGrange<BigDecimal> implements Serializable, Cloneable {

  /**
   * The name of the type.
   *
   * @see #getType()
   */
  public static final String TYPE_NAME = "numrange";

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
  public PGnumrange(@Nullable BigDecimal lowerBound, boolean lowerInclusive,
      @Nullable BigDecimal upperBound, boolean upperInclusive) {
    super(lowerBound, lowerInclusive, upperBound, upperInclusive);
    setType(TYPE_NAME);
  }

  /**
   * Initialize a range with a given bounds.
   *
   * @param lowerBound the lower bound, inclusive, {@code null} if not set
   * @param upperBound the upper bound, exclusive, {@code null} if not set
   */
  public PGnumrange(@Nullable BigDecimal lowerBound, @Nullable BigDecimal upperBound) {
    super(lowerBound, upperBound);
    setType(TYPE_NAME);
  }

  /**
   * Required constructor, initializes an empty range.
   */
  public PGnumrange() {
    setType(TYPE_NAME);
  }

  /**
   * Initialize a range with a given range string representation.
   *
   * @param value String represented range (e.g. '[1.1,3.3)')
   * @throws SQLException Is thrown if the string representation has an unknown format
   * @see #setValue(String)
   */
  public PGnumrange(@Nullable String value) throws SQLException {
    super(value);
    setType(TYPE_NAME);
  }

  @Override
  protected boolean nullSafeEquals(@Nullable BigDecimal o1, @Nullable BigDecimal o2) {
    if (o1 == null) {
      return o2 == null;
    }
    if (o2 == null) {
      return false;
    }
    // new BigDecimal("1.0").equals(new BigDecimal("1.00"))
    // returns false because the number of decimal places is considered
    return o1.compareTo(o2) == 0;
  }

  @Override
  protected String serializeBound(BigDecimal value) {
    return value.toString();
  }

  @Override
  protected BigDecimal parseToken(String token) {
    return new BigDecimal(token);
  }

}
