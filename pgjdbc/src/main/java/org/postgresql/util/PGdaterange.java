package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.sql.SQLException;
import java.time.LocalDate;

public class PGdaterange extends PGrange<LocalDate> implements Serializable, Cloneable {

  /**
   * Initialize a range with a given bounds.
   *
   * @param lowerBound     the lower bound, {@code null} if not set
   * @param lowerInclusive {@code true} to make the lower bound is inclusive,
   *                       {@code false} to make the lower bound is exclusive
   * @param upperBound     the upper bound, {@code null} if not set
   * @param upperInclusive {@code true} to make the upper bound is inclusive,
   *                       {@code false} to make the upper bound is exclusive
   */
  public PGdaterange(@Nullable LocalDate lowerBound, @Nullable boolean lowerInclusive,
      LocalDate upperBound, boolean upperInclusive) {
    super(lowerBound, lowerInclusive, upperBound, upperInclusive);
    setType("daterange");
  }

  /**
   * Initialize a range with a given bounds.
   *
   * @param lowerBound the lower bound, inclusive, {@code null} if not set
   * @param upperBound the upper bound, exclusive, {@code null} if not set
   */
  public PGdaterange(@Nullable LocalDate lowerBound, @Nullable LocalDate upperBound) {
    super(lowerBound, upperBound);
    setType("daterange");
  }

  /**
   * Required constructor, initializes an empty range.
   */
  public PGdaterange() {
    setType("daterange");
  }

  /**
   * Initialize a range with a given range string representation.
   *
   * @param value String represented range (e.g. '[2020-01-01,2021-03-03)')
   * @throws SQLException Is thrown if the string representation has an unknown format
   * @see #setValue(String)
   */
  public PGdaterange(String value) throws SQLException {
    super(value);
    setType("daterange");
  }

  @Override
  protected @NonNull String serializeBound(LocalDate value) {
    return value.toString();
  }

  @Override
  protected LocalDate parseToken(String token) {
    return LocalDate.parse(token);
  }

}
