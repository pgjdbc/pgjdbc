package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

public class PGtstzrange extends PGrange<OffsetDateTime> implements Serializable, Cloneable {

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
  public PGtstzrange(@Nullable OffsetDateTime lowerBound, boolean lowerInclusive,
      @Nullable OffsetDateTime upperBound, boolean upperInclusive) {
    super(lowerBound, lowerInclusive, upperBound, upperInclusive);
    setType("tstzrange");
  }

  /**
   * Initialize a range with a given bounds.
   *
   * @param lowerBound the lower bound, inclusive, {@code null} if not set
   * @param upperBound the upper bound, exclusive, {@code null} if not set
   */
  public PGtstzrange(@Nullable OffsetDateTime lowerBound, @Nullable OffsetDateTime upperBound) {
    super(lowerBound, upperBound);
    setType("tstzrange");
  }

  /**
   * Required constructor, initializes an empty range.
   */
  public PGtstzrange() {
    setType("tstzrange");
  }

  /**
   * Initialize a range with a given range string representation.
   *
   * @param value String represented range (e.g. '["2019-08-07 12:23:40+00","2019-11-30
   *              18:29:23+00")')
   * @throws SQLException Is thrown if the string representation has an unknown format
   * @see #setValue(String)
   */
  public PGtstzrange(String value) throws SQLException {
    super(value);
    setType("tstzrange");
  }

  @Override
  protected @NonNull String serializeBound(OffsetDateTime value) {
    return FORMATTER.format(value);
  }

  @Override
  protected OffsetDateTime parseToken(String token) {
    return OffsetDateTime.parse(token, FORMATTER);
  }

  @Override
  protected boolean nullSafeEquals(@Nullable OffsetDateTime o1, @Nullable OffsetDateTime o2) {
    if (o1 == null) {
      return o2 == null;
    }
    if (o2 == null) {
      return false;
    }
    // We want to allow equivalent dates (different offset, but same instant in time) to be
    // considered equal
    return o1.isEqual(o2);
  }

  private static final DateTimeFormatter FORMATTER = new DateTimeFormatterBuilder()
      .appendLiteral('"')
      .appendPattern("yyyy-MM-dd HH:mm:ss")
      .optionalStart()
      .appendLiteral(".")
      .appendFraction(ChronoField.NANO_OF_SECOND, 1, 6, false)
      .optionalEnd()
      .appendPattern("X")
      .appendLiteral('"')
      .toFormatter(Locale.ENGLISH);
}
